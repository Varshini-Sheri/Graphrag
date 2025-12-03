package graphrag.api

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.{StatusCodes, HttpMethods}
import akka.http.scaladsl.model.headers._
import scala.concurrent.ExecutionContext
import scala.util.{Success, Failure}
import graphrag.core.config._
import graphrag.core.utils.Logging
import graphrag.neo4j.Neo4jUtils
import graphrag.llm.OllamaClient
import com.typesafe.config.ConfigFactory

object HttpServer extends App with Logging {

  implicit val system: ActorSystem = ActorSystem("graphrag-api")
  implicit val ec: ExecutionContext = system.dispatcher

  // Load configuration
  val config = ConfigFactory.load()

  val neo4jConfig = Neo4jConfig(
    uri = sys.env.getOrElse("NEO4J_URI", config.getString("neo4j.uri")),
    user = sys.env.getOrElse("NEO4J_USER", config.getString("neo4j.user")),
    password = sys.env.getOrElse("NEO4J_PASSWORD", config.getString("neo4j.password")),
    database = sys.env.getOrElse("NEO4J_DATABASE", config.getString("neo4j.database"))
  )

  val ollamaConfig = OllamaConfig(
    endpoint = sys.env.getOrElse("OLLAMA_ENDPOINT", config.getString("ollama.endpoint")),
    model = sys.env.getOrElse("OLLAMA_MODEL", config.getString("ollama.model")),
    temperature = config.getDouble("ollama.temperature"),
    timeoutMs = config.getInt("ollama.timeoutMs"),
    maxRetries = config.getInt("ollama.maxRetries")
  )

  // Initialize services
  logInfo("Initializing services...")
  val driver = Neo4jUtils.createDriver(neo4jConfig)
  val ollamaClient = OllamaClient(ollamaConfig)

  val queryService = QueryService(driver, ollamaClient)
  val evidenceService = EvidenceService(driver)
  val exploreService = ExploreService(driver)
  val explainService = ExplainService(driver, ollamaClient)
  val jobsService = JobsService()

  // Create routes
  val apiRoutes = new Routes(
    queryService,
    evidenceService,
    exploreService,
    explainService,
    jobsService
  )

  // CORS support
  val corsHeaders = List(
    `Access-Control-Allow-Origin`.*,
    `Access-Control-Allow-Methods`(HttpMethods.GET, HttpMethods.POST, HttpMethods.PUT, HttpMethods.DELETE, HttpMethods.OPTIONS),
    `Access-Control-Allow-Headers`("Content-Type", "Authorization")
  )

  val routesWithCors = respondWithHeaders(corsHeaders) {
    options {
      complete(StatusCodes.OK)
    } ~ apiRoutes.routes
  }

  // Start server
  val port = sys.env.getOrElse("API_PORT", "8080").toInt
  val host = sys.env.getOrElse("API_HOST", "0.0.0.0")

  Http().newServerAt(host, port).bind(routesWithCors).onComplete {
    case Success(binding) =>
      logInfo(s"GraphRAG API server online at http://$host:$port/")
      logInfo("Available endpoints:")
      logInfo("  GET  /v1/health")
      logInfo("  POST /v1/query")
      logInfo("  GET  /v1/jobs/{jobId}")
      logInfo("  GET  /v1/jobs/{jobId}/result")
      logInfo("  GET  /v1/graph/concept/{id}/neighbors")
      logInfo("  GET  /v1/graph/statistics")
      logInfo("  GET  /v1/evidence/concept/{id}")
      logInfo("  GET  /v1/explain/concept/{id}")
    case Failure(exception) =>
      logError(s"Failed to bind HTTP server: ${exception.getMessage}", exception)
      system.terminate()
  }

  sys.addShutdownHook {
    logInfo("Shutting down API server...")
    driver.close()
    system.terminate()
  }
}
