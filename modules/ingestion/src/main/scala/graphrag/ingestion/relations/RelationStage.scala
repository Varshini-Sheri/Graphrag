package graphrag.ingestion.relations

import graphrag.core.model.{Concept, Mentions, CoOccur}
import graphrag.core.utils.Logging
import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor, ListState, ListStateDescriptor}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.apache.flink.util.Collector
import org.apache.flink.configuration.Configuration
import org.apache.flink.api.scala._

import scala.collection.JavaConverters._

/**
 * Flink KeyedProcessFunction for building co-occurrence relations.
 * Uses Flink state to track concepts per chunk (keyed by chunkId).
 */
class RelationStage extends KeyedProcessFunction[String, Mentions, CoOccur] with Logging {

  @transient private var conceptsState: ListState[Concept] = _
  @transient private var pairCountState: ValueState[java.util.Map[String, java.lang.Long]] = _

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)

    val conceptsDescriptor = new ListStateDescriptor[Concept](
      "concepts",
      RelationStage.conceptTypeInfo
    )
    conceptsState = getRuntimeContext.getListState(conceptsDescriptor)

    val pairCountDescriptor = new ValueStateDescriptor[java.util.Map[String, java.lang.Long]](
      "pairCounts",
      classOf[java.util.Map[String, java.lang.Long]]
    )
    pairCountState = getRuntimeContext.getState(pairCountDescriptor)

    logInfo("RelationStage state initialized")
  }

  override def processElement(
                               mention: Mentions,
                               ctx: KeyedProcessFunction[String, Mentions, CoOccur]#Context,
                               out: Collector[CoOccur]
                             ): Unit = {

    val chunkId = mention.chunkId
    val concept = mention.concept

    val existingConcepts = conceptsState.get().asScala.toSeq

    var pairCounts = pairCountState.value()
    if (pairCounts == null) {
      pairCounts = new java.util.HashMap[String, java.lang.Long]()
    }

    existingConcepts.foreach { existing =>
      if (existing.conceptId != concept.conceptId) {
        val (a, b) =
          if (existing.conceptId < concept.conceptId) (existing, concept)
          else (concept, existing)

        val key = s"${a.conceptId}:${b.conceptId}"
        val newFreq = Option(pairCounts.get(key)).map(_.toLong).getOrElse(0L) + 1L
        pairCounts.put(key, newFreq)

        val co = CoOccur(
          a = a,
          b = b,
          windowId = chunkId,
          freq = newFreq
        )

        // 🔍 DEBUG: log every emitted co-occurrence
        logInfo(
          s"[RelationStage] CoOccur: ${a.lemma} <-> ${b.lemma} " +
            s"in chunk=$chunkId freq=$newFreq"
        )

        out.collect(co)
      }
    }

    conceptsState.add(concept)
    pairCountState.update(pairCounts)
  }
}

object RelationStage {

  implicit val conceptTypeInfo: TypeInformation[Concept] =
    TypeInformation.of(classOf[Concept])

  implicit val coOccurTypeInfo: TypeInformation[CoOccur] =
    TypeInformation.of(classOf[CoOccur])

  def apply(): RelationStage = new RelationStage()
}
