package graphrag.job

import graphrag.core.model._
import graphrag.core.utils.Logging
import org.apache.flink.streaming.api.scala._

object Quality extends Logging {

  /**
   * Filter relations by minimum confidence
   */
  def filterByConfidence(relations: DataStream[ScoredRelation], minConfidence: Double): DataStream[ScoredRelation] = {
    relations.filter(_.confidence >= minConfidence)
      .name(s"filter-confidence-$minConfidence")
  }

  /**
   * Calculate quality metrics for a batch of relations
   */
  def calculateMetrics(relations: Seq[ScoredRelation]): Map[String, Double] = {
    if (relations.isEmpty) {
      Map(
        "count" -> 0.0,
        "avgConfidence" -> 0.0,
        "minConfidence" -> 0.0,
        "maxConfidence" -> 0.0
      )
    } else {
      val confidences = relations.map(_.confidence)
      Map(
        "count" -> relations.size.toDouble,
        "avgConfidence" -> confidences.sum / confidences.size,
        "minConfidence" -> confidences.min,
        "maxConfidence" -> confidences.max
      )
    }
  }

  /**
   * Predicate distribution for analysis
   */
  def predicateDistribution(relations: Seq[ScoredRelation]): Map[String, Int] = {
    relations.groupBy(_.predicate).map { case (pred, rels) => pred -> rels.size }
  }

  /**
   * Log quality metrics
   */
  def logQualityReport(relations: Seq[ScoredRelation]): Unit = {
    val metrics = calculateMetrics(relations)
    val distribution = predicateDistribution(relations)

    logInfo("=" * 50)
    logInfo("Quality Report")
    logInfo("=" * 50)
    logInfo(s"Total relations: ${metrics("count").toInt}")
    logInfo(s"Avg confidence: ${metrics("avgConfidence")}")
    logInfo(s"Min confidence: ${metrics("minConfidence")}")
    logInfo(s"Max confidence: ${metrics("maxConfidence")}")
    logInfo("")
    logInfo("Predicate distribution:")
    distribution.toSeq.sortBy(-_._2).foreach { case (pred, count) =>
      logInfo(s"  $pred: $count")
    }
    logInfo("=" * 50)
  }
}