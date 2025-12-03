package graphrag.ingestion.relations

import graphrag.core.model._
import graphrag.core.utils.Logging
import org.apache.flink.util.Collector

import scala.collection.mutable

object CoOccurrence extends Logging {

  /**
   * Build co-occurrence output directly into the Collector.
   * Java API version required by RichFlatMapFunction.
   */
  def buildPairs(mention: Mentions, out: Collector[CoOccur]): Unit = {
    val chunkId = mention.chunkId
    val concept = mention.concept

    // Temporary buffer stored inside mention (since CoOccur is window local)
    val seen = mention.seenConcepts  // <-- YOU MUST add this field OR internal state

    // Build pairs
    seen.foreach { existing =>
      if (existing.conceptId != concept.conceptId) {
        val (a, b) =
          if (existing.conceptId < concept.conceptId) (existing, concept)
          else (concept, existing)

        out.collect(
          CoOccur(
            a = a,
            b = b,
            windowId = chunkId,
            freq = 1L
          )
        )
      }
    }

    // Update local state
    seen += concept
  }
}
