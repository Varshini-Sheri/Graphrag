package graphrag.api

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.{Encoder, Json}
import graphrag.core.utils.Logging

class Routes(
              queryService: QueryService,
              evidenceService: EvidenceService,
              exploreService: ExploreService,
              explainService: ExplainService,
              jobsService: JobsService
            ) extends Logging {

  // Custom encoders for types with Map[String, Any]
  implicit val chunkEvidenceEncoder: Encoder[ChunkEvidence] = Encoder.instance { c =>
    Json.obj(
      "chunkId" -> Json.fromString(c.chunkId),
      "text" -> Json.fromString(c.text),
      "docId" -> Json.fromString(c.docId)
    )
  }

  implicit val evidenceEncoder: Encoder[Evidence] = Encoder.instance { e =>
    Json.obj(
      "predicate" -> Json.fromString(e.predicate),
      "confidence" -> Json.fromDoubleOrNull(e.confidence),
      "explanation" -> Json.fromString(e.explanation),
      "chunks" -> Json.arr(e.chunks.map(chunkEvidenceEncoder.apply): _*)
    )
  }

  val routes: Route = pathPrefix("v1") {
    healthRoute ~ queryRoutes ~ evidenceRoutes ~ exploreRoutes ~ explainRoutes ~ jobRoutes
  }

  private def healthRoute: Route = path("health") {
    get {
      complete(Json.obj(
        "status" -> Json.fromString("ok"),
        "service" -> Json.fromString("graphrag-api"),
        "timestamp" -> Json.fromLong(System.currentTimeMillis())
      ))
    }
  }

  private def queryRoutes: Route = pathPrefix("query") {
    post {
      entity(as[QueryRequest]) { request =>
        logInfo(s"Query: ${request.query}")
        val result = queryService.query(request.query, request.maxResults.getOrElse(10))
        complete(result)
      }
    }
  }

  private def evidenceRoutes: Route = pathPrefix("evidence") {
    path("concept" / Segment) { conceptId =>
      get {
        val evidence = evidenceService.getEvidenceForConcept(conceptId)
        complete(Json.obj(
          "conceptId" -> Json.fromString(conceptId),
          "evidence" -> Json.arr(evidence.map(evidenceEncoder.apply): _*)
        ))
      }
    }
  }

  private def exploreRoutes: Route = pathPrefix("graph") {
    path("concept" / Segment / "neighbors") { conceptId =>
      get {
        parameters("depth".as[Int].withDefault(1)) { depth =>
          import ExploreServiceEncoders._
          val neighborhood = exploreService.getNeighborhood(conceptId, depth)
          complete(neighborhood.asJson)
        }
      }
    } ~
      path("statistics") {
        get {
          val stats = exploreService.getStatistics()
          complete(stats)
        }
      } ~
      path("search") {
        get {
          parameters("pattern", "limit".as[Int].withDefault(20)) { (pattern, limit) =>
            val concepts = exploreService.searchConcepts(pattern, limit)
            complete(concepts)
          }
        }
      }
  }

  private def explainRoutes: Route = pathPrefix("explain") {
    path("concept" / Segment) { conceptId =>
      get {
        val result = explainService.explainConcept(conceptId)
        complete(result)
      }
    }
  }

  private def jobRoutes: Route = pathPrefix("jobs") {
    path(Segment / "result") { jobId =>
      get {
        // GET /v1/jobs/{jobId}/result
        jobsService.getResult(jobId) match {
          case Some(result) => complete(result)
          case None => complete(StatusCodes.NotFound -> Json.obj(
            "error" -> Json.fromString("Result not found")
          ))
        }
      }
    } ~
      path(Segment) { jobId =>
        get {
          // GET /v1/jobs/{jobId}
          jobsService.getJob(jobId) match {
            case Some(job) => complete(job)
            case None => complete(StatusCodes.NotFound -> Json.obj(
              "error" -> Json.fromString("Job not found")
            ))
          }
        }
      }
  }
}

case class QueryRequest(query: String, maxResults: Option[Int])