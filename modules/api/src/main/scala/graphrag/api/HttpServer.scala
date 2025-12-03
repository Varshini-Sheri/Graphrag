package graphrag.api

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.{StatusCodes, HttpMethods}
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.{Directive0, ExceptionHandler, Route}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}
import graphrag.core.config.GraphRagConfig
import graphrag.core.utils.Logging
import graphrag.neo4j.Neo4jUtils
import graphrag.llm.OllamaClient
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._

object HttpServer extends App with Logging {

  implicit val system: ActorSystem = ActorSystem("graphrag-api")
  implicit val ec: ExecutionContext = system.dispatcher

  logInfo("Loading GraphRAG configuration...")
  val config = GraphRagConfig.load()

  val driver = Neo4jUtils.createDriver(config.neo4j)
  val ollamaClient = OllamaClient(config.ollama)

  val jobsService = JobsService(config)
  val queryService = QueryService(driver, ollamaClient, jobsService)
  val evidenceService = EvidenceService(driver)
  val exploreService = ExploreService(driver)
  val explainService = ExplainService(driver, ollamaClient)

  val apiRoutes = new Routes(
    queryService,
    evidenceService,
    exploreService,
    explainService,
    jobsService
  )

  // -----------------------------------
  // CORS
  // -----------------------------------
  private val corsHeaders = List(
    `Access-Control-Allow-Origin`.*,
    `Access-Control-Allow-Methods`(
      HttpMethods.GET,
      HttpMethods.POST,
      HttpMethods.PUT,
      HttpMethods.DELETE,
      HttpMethods.OPTIONS
    ),
    `Access-Control-Allow-Headers`("Content-Type", "Authorization", "X-Trace-Id", "Idempotency-Key"),
    `Access-Control-Max-Age`(3600)
  )

  def corsDirective: Directive0 =
    respondWithHeaders(corsHeaders)

  // -----------------------------------
  // Trace ID
  // -----------------------------------
  def traceIdDirective: Directive0 =
    extractRequestContext.flatMap { ctx =>
      val incoming = ctx.request.headers.collectFirst { case h if h.lowercaseName == "x-trace-id" => h.value }
      val traceId = incoming.getOrElse(s"tr-${System.currentTimeMillis()}")
      respondWithHeader(RawHeader("X-Trace-Id", traceId))
    }

  // -----------------------------------
  // Exception Handler
  // -----------------------------------
  import RoutesEncoders._

  implicit def exceptionHandler: ExceptionHandler = ExceptionHandler {
    case e: IllegalArgumentException =>
      complete(
        StatusCodes.BadRequest ->
          ErrorResponse("Bad Request", "INVALID_REQUEST", e.getMessage, Some(s"tr-${System.currentTimeMillis()}")).asJson
      )

    case e: NoSuchElementException =>
      complete(
        StatusCodes.NotFound ->
          ErrorResponse("Not Found", "RESOURCE_NOT_FOUND", e.getMessage, Some(s"tr-${System.currentTimeMillis()}")).asJson
      )

    case e: Throwable =>
      complete(
        StatusCodes.InternalServerError ->
          ErrorResponse("Internal Server Error", "INTERNAL_ERROR", "An unexpected error occurred", Some(s"tr-${System.currentTimeMillis()}")).asJson
      )
  }

  // -----------------------------------
  // Rate Limit (Very Simple)
  // -----------------------------------
  private val rateLimitCache = scala.collection.concurrent.TrieMap[String, (Long, Int)]()
  private val RATE_LIMIT = 100
  private val RATE_WINDOW = 60000

  def rateLimit(clientId: String): Directive0 = {
    val now = System.currentTimeMillis()
    val (windowStart, count) = rateLimitCache.getOrElse(clientId, (now, 0))

    if (now - windowStart > RATE_WINDOW) {
      rateLimitCache.put(clientId, (now, 1))
      pass
    } else if (count >= RATE_LIMIT) {
      complete(
        StatusCodes.TooManyRequests ->
          ErrorResponse(
            "Too Many Requests",
            "RATE_LIMIT_EXCEEDED",
            s"Rate limit of $RATE_LIMIT requests/min exceeded",
            Some(s"tr-${System.currentTimeMillis()}")
          ).asJson
      )
    } else {
      rateLimitCache.put(clientId, (windowStart, count + 1))
      pass
    }
  }

  // -----------------------------------
  // FINAL ROUTES WITH MIDDLEWARE
  // -----------------------------------
  val routesWithMiddleware: Route =
    corsDirective {
      traceIdDirective {
        handleExceptions(exceptionHandler) {

          // First handle OPTIONS for CORS
          options {
            complete(StatusCodes.OK)
          } ~
            // Actual routed requests
            optionalHeaderValueByName("Authorization") { authOpt =>
              val clientId = authOpt.getOrElse("anonymous")

              rateLimit(clientId) {
                apiRoutes.routes
              }
            }
        }
      }
    }

  // -----------------------------------
  // Start server
  // -----------------------------------
  val port = sys.env.getOrElse("API_PORT", "8080").toInt
  val host = sys.env.getOrElse("API_HOST", "0.0.0.0")

  Http()
    .newServerAt(host, port)
    .bind(routesWithMiddleware)
    .onComplete {
      case Success(binding) =>
        logInfo(s"GraphRAG API started at http://$host:$port")
      case Failure(e) =>
        logError(s"Failed to bind: ${e.getMessage}", e)
        system.terminate()
    }

  sys.addShutdownHook {
    logInfo("Shutting down GraphRAG API server...")
    driver.close()
    system.terminate()
  }
}
