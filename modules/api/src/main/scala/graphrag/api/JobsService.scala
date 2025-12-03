package graphrag.api

import graphrag.core.utils.Logging

import java.time.Instant
import java.util.UUID
import scala.collection.mutable

/**
 * Service for tracking asynchronous query jobs.
 * Jobs are created when queries need heavy graph expansion or LLM rescoring.
 */
class JobsService() extends Logging {

  private val jobs = mutable.Map[String, JobInfo]()
  private val results = mutable.Map[String, QueryResult]()

  /**
   * Create a new job for an async query
   */
  def createJob(query: String): JobInfo = {
    val jobId = s"job-${UUID.randomUUID().toString.take(8)}"
    val job = JobInfo(
      jobId = jobId,
      query = query,
      state = "PENDING",
      submittedAt = Instant.now().toString,
      startedAt = None,
      finishedAt = None,
      resultLink = None
    )

    jobs.put(jobId, job)
    logInfo(s"Created async query job: $jobId for query: $query")
    job
  }

  /**
   * Get job status
   */
  def getJob(jobId: String): Option[JobInfo] = {
    logInfo(s"Fetching job status: $jobId")
    jobs.get(jobId)
  }

  /**
   * Update job to running state
   */
  def markRunning(jobId: String): Unit = {
    jobs.get(jobId).foreach { job =>
      val updated = job.copy(
        state = "RUNNING",
        startedAt = Some(Instant.now().toString)
      )
      jobs.put(jobId, updated)
      logInfo(s"Job $jobId marked as RUNNING")
    }
  }

  /**
   * Mark job as succeeded
   */
  def markSucceeded(jobId: String, result: QueryResult): Unit = {
    jobs.get(jobId).foreach { job =>
      val updated = job.copy(
        state = "SUCCEEDED",
        finishedAt = Some(Instant.now().toString),
        resultLink = Some(s"/v1/jobs/$jobId/result")
      )
      jobs.put(jobId, updated)
      results.put(jobId, result)
      logInfo(s"Job $jobId marked as SUCCEEDED")
    }
  }

  /**
   * Mark job as failed
   */
  def markFailed(jobId: String, error: String): Unit = {
    jobs.get(jobId).foreach { job =>
      val updated = job.copy(
        state = "FAILED",
        finishedAt = Some(Instant.now().toString)
      )
      jobs.put(jobId, updated)
      logError(s"Job $jobId marked as FAILED: $error")
    }
  }

  /**
   * Get query result
   */
  def getResult(jobId: String): Option[QueryResult] = {
    logInfo(s"Fetching result for job: $jobId")
    results.get(jobId)
  }
}

/**
 * Job information for async queries (matches homework spec)
 */
case class JobInfo(
                    jobId: String,
                    query: String,
                    state: String,  // PENDING, RUNNING, SUCCEEDED, FAILED
                    submittedAt: String,
                    startedAt: Option[String],
                    finishedAt: Option[String],
                    resultLink: Option[String]
                  )

object JobsService {
  def apply(): JobsService = new JobsService()
}