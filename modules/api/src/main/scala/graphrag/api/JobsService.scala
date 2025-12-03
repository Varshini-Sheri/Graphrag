package graphrag.api

import graphrag.core.config.GraphRagConfig
import graphrag.core.utils.Logging
import io.circe._
import io.circe.generic.semiauto._

import java.time.Instant
import java.util.UUID
import scala.collection.mutable

class JobsService(config: GraphRagConfig) extends Logging {

  private val jobs = mutable.Map[String, JobInfo]()
  private val jobResults = mutable.Map[String, JobResult]()

  def submitJob(jobType: String, params: Map[String, String] = Map.empty): JobInfo = {
    val jobId = s"job-${UUID.randomUUID().toString.take(8)}"

    val job = JobInfo(
      jobId = jobId,
      jobType = jobType,
      state = JobState.Running,
      params = params,
      startedAt = Instant.now(),
      finishedAt = None,
      progress = 0.0,
      message = "Job submitted"
    )

    jobs.put(jobId, job)
    logInfo(s"Submitted job $jobId of type $jobType")
    startJobAsync(job)
    job
  }

  def getJob(jobId: String): Option[JobInfo] = jobs.get(jobId)

  def getJobResult(jobId: String): Option[JobResult] = {
    jobs.get(jobId) match {
      case Some(job) if job.state == JobState.Succeeded =>
        jobResults.get(jobId)
      case _ => None
    }
  }

  def listJobs(state: Option[JobState] = None,
               jobType: Option[String] = None,
               limit: Int = 50): Seq[JobInfo] = {
    jobs.values
      .filter(j => state.forall(_ == j.state))
      .filter(j => jobType.forall(_ == j.jobType))
      .toSeq
      .sortBy(_.startedAt)
      .reverse
      .take(limit)
  }

  def cancelJob(jobId: String): Boolean = {
    jobs.get(jobId) match {
      case Some(job) if job.state == JobState.Running =>
        jobs.put(jobId, job.copy(
          state = JobState.Cancelled,
          finishedAt = Some(Instant.now()),
          message = "Job cancelled by user"
        ))
        true
      case _ => false
    }
  }

  def deleteJob(jobId: String): Boolean = {
    jobResults.remove(jobId)
    jobs.remove(jobId).isDefined
  }

  def updateProgress(jobId: String, progress: Double, msg: String): Unit = {
    jobs.get(jobId).foreach { job =>
      jobs.put(jobId, job.copy(progress = progress, message = msg))
    }
  }

  def completeJob(jobId: String, result: JobResult): Unit = {
    jobs.get(jobId).foreach { job =>
      jobs.put(jobId, job.copy(
        state = JobState.Succeeded,
        finishedAt = Some(Instant.now()),
        progress = 1.0,
        message = "Job completed successfully"
      ))
      jobResults.put(jobId, result)
    }
  }

  def failJob(jobId: String, msg: String): Unit = {
    jobs.get(jobId).foreach { job =>
      jobs.put(jobId, job.copy(
        state = JobState.Failed,
        finishedAt = Some(Instant.now()),
        message = msg
      ))
    }
  }

  private def startJobAsync(job: JobInfo): Unit = {
    val t = new Thread(() => {
      try {
        job.jobType match {
          case "query"   => runQueryJob(job)
          case "ingest"  => runIngestionJob(job)
          case "reindex" => runReindexJob(job)
          case "export"  => runExportJob(job)
          case _         => failJob(job.jobId, s"Unknown job type ${job.jobType}")
        }
      } catch {
        case e: Exception =>
          logError(s"Job ${job.jobId} failed", e)
          failJob(job.jobId, e.getMessage)
      }
    })
    t.setDaemon(true)
    t.start()
  }

  private def runQueryJob(job: JobInfo): Unit = {
    updateProgress(job.jobId, 0.1, "Parsing query...")
    Thread.sleep(500)
    updateProgress(job.jobId, 0.3, "Searching graph...")
    Thread.sleep(800)
    updateProgress(job.jobId, 0.6, "Retrieving evidence...")
    Thread.sleep(900)
    updateProgress(job.jobId, 0.9, "Generating response...")
    Thread.sleep(400)

    completeJob(job.jobId, JobResult(
      summary = "Query job completed",
      groups = Seq.empty,
      citations = Seq.empty
    ))
  }

  private def runIngestionJob(job: JobInfo): Unit = {
    (1 to 10).foreach { i =>
      Thread.sleep(800)
      updateProgress(job.jobId, i * 0.1, s"Processing batch $i of 10")
    }
    completeJob(job.jobId, JobResult("Ingestion completed", Seq.empty, Seq.empty))
  }

  private def runReindexJob(job: JobInfo): Unit = {
    Thread.sleep(1500)
    updateProgress(job.jobId, 0.5, "Rebuilding indexes...")
    Thread.sleep(1200)
    completeJob(job.jobId, JobResult("Reindex completed", Seq.empty, Seq.empty))
  }

  private def runExportJob(job: JobInfo): Unit = {
    val outputPath = job.params.getOrElse("output", "/tmp/export.json")
    updateProgress(job.jobId, 0.5, s"Exporting to $outputPath...")
    Thread.sleep(1500)
    completeJob(job.jobId, JobResult(s"Exported to $outputPath", Seq.empty, Seq.empty))
  }
}

/** ---------- MODELS ---------- **/

case class JobInfo(
                    jobId: String,
                    jobType: String,
                    state: JobState,
                    params: Map[String, String],
                    startedAt: Instant,
                    finishedAt: Option[Instant],
                    progress: Double,
                    message: String
                  )

case class JobResult(
                      summary: String,
                      groups: Seq[ResultGroup],
                      citations: Seq[Citation]
                    )

case class Citation(
                     paperId: String,
                     evidenceId: String,
                     span: String
                   )

/** ---------- STATE ENUM ---------- **/
sealed trait JobState
object JobState {
  case object Running   extends JobState
  case object Succeeded extends JobState
  case object Failed    extends JobState
  case object Cancelled extends JobState

  def fromString(s: String): Option[JobState] = s.toUpperCase match {
    case "RUNNING"   => Some(Running)
    case "SUCCEEDED" => Some(Succeeded)
    case "FAILED"    => Some(Failed)
    case "CANCELLED" => Some(Cancelled)
    case _           => None
  }

  implicit val encoder: Encoder[JobState] =
    Encoder.encodeString.contramap(_.toString.toUpperCase)

  implicit val decoder: Decoder[JobState] =
    Decoder.decodeString.emap(s => fromString(s).toRight(s"Invalid job state: $s"))
}

/** ---------- CIRCE ENCODERS ---------- **/

object JobsServiceEncoders {

  implicit val instantEncoder: Encoder[Instant] =
    Encoder.encodeString.contramap(_.toString)

  implicit val instantDecoder: Decoder[Instant] =
    Decoder.decodeString.emap(str =>
      try Right(Instant.parse(str))
      catch { case _: Throwable => Left(s"Invalid Instant: $str") }
    )

  implicit val citationEncoder: Encoder[Citation] = deriveEncoder
  implicit val citationDecoder: Decoder[Citation] = deriveDecoder

  implicit val jobInfoEncoder: Encoder[JobInfo] = deriveEncoder
  implicit val jobInfoDecoder: Decoder[JobInfo] = deriveDecoder

  implicit val jobResultEncoder: Encoder[JobResult] = deriveEncoder
  implicit val jobResultDecoder: Decoder[JobResult] = deriveDecoder
}

/** Companion */
object JobsService {
  def apply(config: GraphRagConfig): JobsService = new JobsService(config)
}
