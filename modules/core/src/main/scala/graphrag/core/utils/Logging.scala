package graphrag.core.utils

import org.slf4j.{Logger, LoggerFactory}

/**
 * Logging trait for consistent logging across all components.
 * Mix this into classes that need logging capabilities.
 */
trait Logging {

  @transient protected lazy val logger: Logger = LoggerFactory.getLogger(getClass)

  protected def logInfo(msg: String): Unit =
    logger.info(msg)

  protected def logInfo(msg: String, args: Any*): Unit =
    logger.info(msg, args.map(_.asInstanceOf[AnyRef]): _*)

  protected def logDebug(msg: String): Unit =
    logger.debug(msg)

  protected def logDebug(msg: String, args: Any*): Unit =
    logger.debug(msg, args.map(_.asInstanceOf[AnyRef]): _*)

  protected def logWarn(msg: String): Unit =
    logger.warn(msg)

  protected def logWarn(msg: String, t: Throwable): Unit =
    logger.warn(msg, t)

  protected def logError(msg: String): Unit =
    logger.error(msg)

  protected def logError(msg: String, t: Throwable): Unit =
    logger.error(msg, t)

  protected def logTrace(msg: String): Unit =
    logger.trace(msg)

  /**
   * Log execution time of a block
   */
  protected def logTimed[T](label: String)(block: => T): T = {
    val start = System.currentTimeMillis()
    try {
      val result = block
      val elapsed = System.currentTimeMillis() - start
      logInfo(s"$label completed in ${elapsed}ms")
      result
    } catch {
      case e: Exception =>
        val elapsed = System.currentTimeMillis() - start
        logError(s"$label failed after ${elapsed}ms", e)
        throw e
    }
  }

  /**
   * Log with context
   */
  protected def logWithContext(context: Map[String, Any])(msg: String): Unit = {
    val contextStr = context.map { case (k, v) => s"$k=$v" }.mkString(", ")
    logInfo(s"[$contextStr] $msg")
  }
}

/**
 * Companion object for static logging
 */
object Logging {

  private val defaultLogger = LoggerFactory.getLogger("graphrag")

  def info(msg: String): Unit = defaultLogger.info(msg)
  def warn(msg: String): Unit = defaultLogger.warn(msg)
  def error(msg: String): Unit = defaultLogger.error(msg)
  def error(msg: String, t: Throwable): Unit = defaultLogger.error(msg, t)
  def debug(msg: String): Unit = defaultLogger.debug(msg)
}