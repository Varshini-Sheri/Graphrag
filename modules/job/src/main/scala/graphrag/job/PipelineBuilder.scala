package graphrag.job

import graphrag.core.config.GraphRagConfig
import graphrag.core.model._
import graphrag.core.utils.Logging

import graphrag.ingestion.index.IndexSource
import graphrag.ingestion.documents.{Document, DocumentSource}
import graphrag.ingestion.concept.ConceptStage
import graphrag.ingestion.relations.{RelationStage, RelationCandidateGenerator}
import graphrag.ingestion.metadata.MetadataExtractors
import graphrag.llm.LLMStage

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.streaming.api.datastream.DataStream
import org.apache.flink.api.common.functions.MapFunction
import org.apache.flink.api.java.functions.KeySelector

object PipelineBuilder extends Logging {

  def buildPipeline(
                     env: StreamExecutionEnvironment,
                     config: GraphRagConfig
                   ): DataStream[GraphWrite] = {

    // --------------------------------------------
    // 1. Load streams
    // --------------------------------------------

    val docPath   = config.index.documents

    val chunkPath = config.index.chunks

    logInfo(s"Using chunk index: $chunkPath")
    logInfo(s"Using documents:   $docPath")

    val documents: DataStream[Document] =
      env.addSource(DocumentSource.fromPath(docPath))

    val chunks: DataStream[Chunk] =
      env.addSource(IndexSource.fromPath(chunkPath))

    // --------------------------------------------
    // 2. Documents -> Paper nodes
    // --------------------------------------------

    val paperNodes: DataStream[GraphWrite] =
      documents
        .map(
          new MapFunction[Document, GraphWrite] {
            override def map(d: Document): GraphWrite =
              MetadataExtractors.paperFromDocument(d)
          }
        )

    // --------------------------------------------
    // 3. Chunk -> Mentions (concept extraction)
    // --------------------------------------------


    val mentions: DataStream[Mentions] =
      chunks.process(new ConceptStage(config.ollama))
        .name("concept-extraction")

    // --------------------------------------------
    // 4. Mentions -> Co-occurrences
    // --------------------------------------------

    val coOccurs: DataStream[CoOccur] =
      mentions
        .keyBy(
          new KeySelector[Mentions, String] {
            override def getKey(m: Mentions): String = m.chunkId
          }
        )
        .process(RelationStage())

    // --------------------------------------------
    // 5. CoOccurs -> RelationCandidates -> ScoredRelations
    // --------------------------------------------

    val candidates: DataStream[RelationCandidate] =
      RelationCandidateGenerator.generate(coOccurs)

    val relations: DataStream[ScoredRelation] =
      candidates.process(new LLMStage(config.ollama))

    // --------------------------------------------
    // 6. Project all to GraphWrite
    // --------------------------------------------

    val graphWrites: DataStream[GraphWrite] =
      GraphProjector.project(chunks, mentions, coOccurs, relations)

    // --------------------------------------------
    // 7. Union Paper nodes with core graph
    // --------------------------------------------

    paperNodes.union(graphWrites)
  }
}