package graphrag.job

import graphrag.core.model._
import graphrag.core.utils.{HashUtils, Logging}
import graphrag.ingestion.metadata.MetadataExtractors

import org.apache.flink.api.common.functions.{FilterFunction, MapFunction, RichFlatMapFunction}
import org.apache.flink.streaming.api.datastream.DataStream
import org.apache.flink.util.Collector

/**
 * Projects domain streams (Chunk, Mentions, CoOccur, ScoredRelation)
 * into generic GraphWrite primitives.
 */
object GraphProjector extends Logging {

  def project(
               chunks: DataStream[Chunk],
               mentions: DataStream[Mentions],
               coOccurs: DataStream[CoOccur],
               relations: DataStream[ScoredRelation]
             ): DataStream[GraphWrite] = {

    // ----------------------------------------------------------------------
    // 1) Base nodes
    // ----------------------------------------------------------------------

    val chunkNodes = chunks.map(
      new MapFunction[Chunk, GraphWrite] {
        override def map(c: Chunk): GraphWrite =
          GraphWrite.chunkNode(
            chunkId   = c.chunkId,
            docId     = c.docId,
            text      = c.text,
            spanStart = c.span._1,
            spanEnd   = c.span._2,
            sourceUri = c.sourceUri,
            hash      = c.hash
          )
      }
    )

    val conceptNodes = mentions.map(
      new MapFunction[Mentions, GraphWrite] {
        override def map(m: Mentions): GraphWrite =
          GraphWrite.conceptNode(
            conceptId = m.concept.conceptId,
            lemma     = m.concept.lemma,
            surface   = m.concept.surface,
            origin    = m.concept.origin,
            name      = m.concept.name  // ADD THIS LINE
          )
      }
    )

    // ----------------------------------------------------------------------
    // 2) Chunk -> Concept MENTIONS edges
    // ----------------------------------------------------------------------

    val mentionEdges = mentions.map(
      new MapFunction[Mentions, GraphWrite] {
        override def map(m: Mentions): GraphWrite = m.toEdge
      }
    )

    // ----------------------------------------------------------------------
    // 3) CO_OCCURS edges (light threshold)
    // ----------------------------------------------------------------------

    val coOccurEdges =
      coOccurs
        .filter(new FilterFunction[CoOccur] {
          override def filter(c: CoOccur): Boolean = c.freq >= 1L
        })
        .map(
          new MapFunction[CoOccur, GraphWrite] {
            override def map(c: CoOccur): GraphWrite = c.toEdge
          }
        )

    // ----------------------------------------------------------------------
    // 4) RELATES_TO edges from LLM scoring
    // ----------------------------------------------------------------------

    val relationEdges = relations.map(
      new MapFunction[ScoredRelation, GraphWrite] {
        override def map(r: ScoredRelation): GraphWrite = {
          logInfo(
            s"[GraphProjector] RELATES_TO edge: " +
              s"${r.a.lemma} -[${r.predicate}/${r.confidence}]-> ${r.b.lemma}"
          )
          r.toEdge
        }
      }
    )

    // ----------------------------------------------------------------------
    // 5) Chunk BELONGS_TO Paper edges
    // ----------------------------------------------------------------------

    val metadataWrites: DataStream[GraphWrite] =
      chunks.flatMap(
        new RichFlatMapFunction[Chunk, GraphWrite] {
          override def flatMap(c: Chunk, out: Collector[GraphWrite]): Unit = {
            // Chunk BELONGS_TO Paper edge
            MetadataExtractors.fromChunk(c).foreach(out.collect)

            // Extract research entities (Tasks, Datasets, Techniques, Metrics)
            MetadataExtractors.extractResearchMetadata(c.text, c.docId).foreach(out.collect)
          }
        }
      )

    // ----------------------------------------------------------------------
    // 6) Evidence snippets from LLM relations
    // ----------------------------------------------------------------------

    val evidenceWrites: DataStream[GraphWrite] =
      relations.flatMap(
        new RichFlatMapFunction[ScoredRelation, GraphWrite] {
          override def flatMap(r: ScoredRelation, out: Collector[GraphWrite]): Unit = {
            if (r.evidence != null && r.evidence.trim.nonEmpty) {
              val snippetId = "sn_" + HashUtils.sha256Short(r.evidence)

              // Snippet node
              out.collect(
                GraphWrite.snippetNode(
                  snippetId  = snippetId,
                  text       = r.evidence,
                  chunkId    = None,
                  confidence = Some(r.confidence)
                )
              )

              // Evidence edge from concept to snippet
              out.collect(
                GraphWrite.evidenceEdge(
                  conceptId = r.a.conceptId,
                  snippetId = snippetId,
                  role      = "source"
                )
              )
            }
          }
        }
      )

    // ----------------------------------------------------------------------
    // 7) Union everything into one GraphWrite stream
    // ----------------------------------------------------------------------

    chunkNodes
      .union(conceptNodes)
      .union(mentionEdges)
      .union(coOccurEdges)
      .union(relationEdges)
      .union(metadataWrites)
      .union(evidenceWrites)
  }
}