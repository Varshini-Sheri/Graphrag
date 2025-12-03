package graphrag.api

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.{StatusCodes, ContentTypes, HttpEntity}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._
import io.circe.generic.auto._
import graphrag.core.utils.Logging

class Routes(
              queryService: QueryService,
              evidenceService: EvidenceService,
              exploreService: ExploreService,
              explainService: ExplainService,
              jobsService: JobsService
            ) extends Logging {

  // Import all encoders - CRITICAL for compilation
  import QueryServiceEncoders._
  import EvidenceServiceEncoders._
  import ExploreServiceEncoders._
  import ExplainServiceEncoders._
  import JobsServiceEncoders._
  import RoutesEncoders._

  val routes: Route = pathPrefix("v1") {
    queryRoutes ~
      evidenceRoutes ~
      exploreRoutes ~
      explainRoutes ~
      jobRoutes ~
      healthRoute
  }

  /**
   * Health check endpoint
   */
  private def healthRoute: Route = path("health") {
    get {
      complete(Map(
        "status" -> "ok",
        "service" -> "graphrag-api",
        "version" -> "1.0.0"
      ))
    }
  }

  /**
   * Query endpoints matching specification
   */
  private def queryRoutes: Route = pathPrefix("query") {
    post {
      entity(as[QueryRequest]) { request =>
        logInfo(s"Received query: ${request.query}")

        queryService.query(request) match {
          case Left(syncResponse) =>
            // Synchronous response with explainLink added
            val responseWithLink = syncResponse.copy(
              explainLink = Some(s"/v1/explain/trace/req-${System.currentTimeMillis()}")
            )
            complete(StatusCodes.OK -> responseWithLink.asJson)

          case Right(asyncResponse) =>
            // Asynchronous response with 202 Accepted
            complete(StatusCodes.Accepted -> asyncResponse.asJson)
        }
      }
    }
  }

  /**
   * Evidence endpoints matching specification
   */
  private def evidenceRoutes: Route = pathPrefix("evidence") {
    // GET /v1/evidence/{evidenceId}
    path(Segment) { evidenceId =>
      get {
        logInfo(s"Fetching evidence: $evidenceId")

        // Handle both evid:chunk-xyz and fromId->toId formats
        if (evidenceId.contains("->")) {
          val parts = evidenceId.split("->")
          if (parts.length == 2) {
            val evidence = evidenceService.getEvidenceForRelation(parts(0), parts(1))
            if (evidence.nonEmpty) {
              complete(StatusCodes.OK -> evidence.asJson)
            } else {
              complete(StatusCodes.NotFound -> ErrorResponse(
                error = "Not Found",
                code = "EVIDENCE_NOT_FOUND",
                message = s"No evidence found for relation $evidenceId"
              ).asJson)
            }
          } else {
            complete(StatusCodes.BadRequest -> ErrorResponse(
              error = "Bad Request",
              code = "INVALID_EVIDENCE_FORMAT",
              message = "Evidence ID format should be evid:chunk-xyz or fromId->toId"
            ).asJson)
          }
        } else {
          evidenceService.getEvidenceById(evidenceId) match {
            case Some(evidence) =>
              complete(StatusCodes.OK -> evidence.asJson)
            case None =>
              complete(StatusCodes.NotFound -> ErrorResponse(
                error = "Not Found",
                code = "EVIDENCE_NOT_FOUND",
                message = s"Evidence not found: $evidenceId"
              ).asJson)
          }
        }
      }
    }
  }

  /**
   * Graph exploration endpoints matching specification
   */
  private def exploreRoutes: Route = pathPrefix("graph") {
    // GET /v1/graph/concept/{conceptId}/neighbors
    path("concept" / Segment / "neighbors") { conceptId =>
      get {
        parameters(
          "direction".withDefault("both"),
          "depth".as[Int].withDefault(1),
          "limit".as[Int].withDefault(50),
          "edgeTypes".?,
          "offset".as[Int].withDefault(0)
        ) { (direction, depth, limit, edgeTypesOpt, offset) =>
          logInfo(s"Exploring neighborhood: $conceptId")

          val edgeTypes = edgeTypesOpt.map(_.split(",").toSeq)
            .getOrElse(Seq("RELATES_TO", "CO_OCCURS", "MENTIONS"))

          val neighborhood = exploreService.getNeighborhood(
            conceptId, direction, depth, limit, edgeTypes, offset
          )
          complete(StatusCodes.OK -> neighborhood.asJson)
        }
      }
    } ~
      // GET /v1/graph/paths/{fromId}/{toId}
      path("paths" / Segment / Segment) { (fromId, toId) =>
        get {
          parameters(
            "maxLength".as[Int].withDefault(4),
            "limit".as[Int].withDefault(10)
          ) { (maxLength, limit) =>
            logInfo(s"Finding paths: $fromId -> $toId")

            val paths = exploreService.findPaths(fromId, toId, maxLength, limit)
            if (paths.nonEmpty) {
              complete(StatusCodes.OK -> paths.asJson)
            } else {
              complete(StatusCodes.NotFound -> ErrorResponse(
                error = "Not Found",
                code = "PATH_NOT_FOUND",
                message = s"No path found between $fromId and $toId within $maxLength hops"
              ).asJson)
            }
          }
        }
      } ~
      // GET /v1/graph/statistics
      path("statistics") {
        get {
          logInfo("Fetching graph statistics")
          val stats = exploreService.getStatistics()
          complete(StatusCodes.OK -> stats.asJson)
        }
      } ~
      // GET /v1/graph/top-concepts
      path("top-concepts") {
        get {
          parameters(
            "limit".as[Int].withDefault(10),
            "sortBy".withDefault("connections")
          ) { (limit, sortBy) =>
            logInfo(s"Fetching top concepts (sortBy: $sortBy)")
            val concepts = exploreService.getTopConcepts(limit, sortBy)
            complete(StatusCodes.OK -> concepts.asJson)
          }
        }
      } ~
      // GET /v1/graph/search
      path("search") {
        get {
          parameters(
            "pattern",
            "limit".as[Int].withDefault(20),
            "offset".as[Int].withDefault(0)
          ) { (pattern, limit, offset) =>
            logInfo(s"Searching concepts: $pattern")
            val result = exploreService.searchConcepts(pattern, limit, offset)
            complete(StatusCodes.OK -> result.asJson)
          }
        }
      }
  }

  /**
   * Explanation endpoints matching specification
   */
  private def explainRoutes: Route = pathPrefix("explain") {
    // GET /v1/explain/trace/{requestId}
    path("trace" / Segment) { requestId =>
      get {
        logInfo(s"Fetching execution trace: $requestId")

        explainService.getExecutionTrace(requestId) match {
          case Some(trace) =>
            complete(StatusCodes.OK -> trace.asJson)
          case None =>
            complete(StatusCodes.NotFound -> ErrorResponse(
              error = "Not Found",
              code = "TRACE_NOT_FOUND",
              message = s"Execution trace not found: $requestId"
            ).asJson)
        }
      }
    } ~
      // GET /v1/explain/concept/{conceptId}
      path("concept" / Segment) { conceptId =>
        get {
          parameters("includeTrace".as[Boolean].withDefault(false)) { includeTrace =>
            logInfo(s"Explaining concept: $conceptId")

            val result = explainService.explainConcept(conceptId, includeTrace)
            if (result.success) {
              complete(StatusCodes.OK -> result.asJson)
            } else {
              complete(StatusCodes.NotFound -> result.asJson)
            }
          }
        }
      } ~
      // GET /v1/explain/relation/{fromId}/{toId}
      path("relation" / Segment / Segment) { (fromId, toId) =>
        get {
          parameters("includeTrace".as[Boolean].withDefault(false)) { includeTrace =>
            logInfo(s"Explaining relation: $fromId -> $toId")

            val result = explainService.explainRelation(fromId, toId, includeTrace)
            if (result.success) {
              complete(StatusCodes.OK -> result.asJson)
            } else {
              complete(StatusCodes.NotFound -> result.asJson)
            }
          }
        }
      } ~
      // GET /v1/explain/path/{fromId}/{toId}
      path("path" / Segment / Segment) { (fromId, toId) =>
        get {
          parameters(
            "maxHops".as[Int].withDefault(3),
            "includeTrace".as[Boolean].withDefault(false)
          ) { (maxHops, includeTrace) =>
            logInfo(s"Explaining path: $fromId -> $toId")

            val result = explainService.explainPath(fromId, toId, maxHops, includeTrace)
            if (result.success) {
              complete(StatusCodes.OK -> result.asJson)
            } else {
              complete(StatusCodes.NotFound -> result.asJson)
            }
          }
        }
      }
  }

  /**
   * Job management endpoints matching specification
   */
  private def jobRoutes: Route = pathPrefix("jobs") {
    // POST /v1/jobs
    post {
      entity(as[JobSubmitRequest]) { request =>
        logInfo(s"Submitting job: ${request.jobType}")

        val job = jobsService.submitJob(request.jobType, request.params)
        complete(StatusCodes.Accepted -> job.asJson)
      }
    } ~
      // GET /v1/jobs/{jobId}
      path(Segment) { jobId =>
        get {
          logInfo(s"Fetching job status: $jobId")

          jobsService.getJob(jobId) match {
            case Some(job) =>
              val response = Map(
                "jobId" -> job.jobId,
                "state" -> job.state.toString,
                "startedAt" -> job.startedAt.toString,
                "finishedAt" -> job.finishedAt.map(_.toString).getOrElse(""),
                "resultLink" -> s"/v1/jobs/$jobId/result"
              )
              complete(StatusCodes.OK -> response)
            case None =>
              complete(StatusCodes.NotFound -> ErrorResponse(
                error = "Not Found",
                code = "JOB_NOT_FOUND",
                message = s"Job not found: $jobId"
              ).asJson)
          }
        } ~
          delete {
            logInfo(s"Deleting job: $jobId")

            if (jobsService.deleteJob(jobId)) {
              complete(StatusCodes.NoContent)
            } else {
              complete(StatusCodes.NotFound -> ErrorResponse(
                error = "Not Found",
                code = "JOB_NOT_FOUND",
                message = s"Job not found: $jobId"
              ).asJson)
            }
          }
      } ~
      // GET /v1/jobs/{jobId}/result
      path(Segment / "result") { jobId =>
        get {
          logInfo(s"Fetching job result: $jobId")

          jobsService.getJobResult(jobId) match {
            case Some(result) =>
              complete(StatusCodes.OK -> result.asJson)
            case None =>
              jobsService.getJob(jobId) match {
                case Some(job) if job.state != JobState.Succeeded =>
                  complete(StatusCodes.Accepted -> Map(
                    "message" -> "Job not yet completed",
                    "state" -> job.state.toString,
                    "progress" -> job.progress
                  ))
                case _ =>
                  complete(StatusCodes.NotFound -> ErrorResponse(
                    error = "Not Found",
                    code = "JOB_NOT_FOUND",
                    message = s"Job not found or result not available: $jobId"
                  ).asJson)
              }
          }
        }
      } ~
      // GET /v1/jobs (list all jobs)
      pathEnd {
        get {
          parameters(
            "state".?,
            "jobType".?,
            "limit".as[Int].withDefault(50)
          ) { (stateOpt, jobTypeOpt, limit) =>
            logInfo("Listing jobs")

            val stateFilter = stateOpt.flatMap(JobState.fromString)
            val jobs = jobsService.listJobs(stateFilter, jobTypeOpt, limit)
            complete(StatusCodes.OK -> jobs.asJson)
          }
        }
      }
  }
}

/**
 * Error response format matching specification
 */
case class ErrorResponse(
                          error: String,
                          code: String,
                          message: String,
                          traceId: Option[String] = None
                        )

/**
 * Job submit request
 */
case class JobSubmitRequest(
                             jobType: String,
                             params: Map[String, String] = Map.empty
                           )

object RoutesEncoders {
  import io.circe.generic.semiauto._

  implicit val errorResponseEncoder: io.circe.Encoder[ErrorResponse] = deriveEncoder
  implicit val jobSubmitRequestDecoder: io.circe.Decoder[JobSubmitRequest] = deriveDecoder
}