package graphrag.ingestion.concept

import graphrag.core.model.{Chunk, Mentions}
import graphrag.core.config.OllamaConfig
import graphrag.core.utils.Logging
import graphrag.llm.OllamaClient

import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.configuration.Configuration
import org.apache.flink.util.Collector

/**
 * LLM-ONLY concept extraction.
 * NO heuristics, NO fallbacks - pure LLM extraction.
 */
class ConceptStage(ollamaConfig: OllamaConfig)
  extends ProcessFunction[Chunk, Mentions] with Logging {

  @transient private var client: OllamaClient = _
  private var conceptCount = 0L
  private var llmSuccessCount = 0L
  private var llmFailCount = 0L
  private var skippedShortChunks = 0L

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    client = OllamaClient(ollamaConfig)
    logInfo(s"ConceptStage initialized - LLM ONLY mode")
    logInfo(s"  Model: ${ollamaConfig.model}")
    logInfo(s"  Endpoint: ${ollamaConfig.endpoint}")
    logInfo(s"  Timeout: ${ollamaConfig.timeoutMs}ms")
  }

  override def processElement(
                               chunk: Chunk,
                               ctx: ProcessFunction[Chunk, Mentions]#Context,
                               out: Collector[Mentions]
                             ): Unit = {
    try {
      // Skip very short chunks (not worth LLM call)
      if (chunk.text.length < 100) {
        skippedShortChunks += 1
        return
      }

      // LLM extraction ONLY
      val llmMentions = try {
        val mentions = LlmConceptExtractor.extractMentions(chunk, client)

        if (mentions.nonEmpty) {
          llmSuccessCount += 1
          logDebug(s"LLM extracted ${mentions.size} concepts from chunk ${chunk.chunkId}")
        } else {
          llmFailCount += 1
          logWarn(s"LLM returned 0 concepts for chunk ${chunk.chunkId}")
        }

        mentions
      } catch {
        case e: Exception =>
          llmFailCount += 1
          logError(s"LLM extraction failed for chunk ${chunk.chunkId}: ${e.getMessage}")
          Seq.empty
      }

      // Emit all LLM-extracted mentions
      llmMentions.foreach(out.collect)
      conceptCount += llmMentions.size

      // Log progress every 100 chunks
      if ((llmSuccessCount + llmFailCount) % 100 == 0) {
        val successRate = if (llmSuccessCount + llmFailCount > 0) {
          (llmSuccessCount.toDouble / (llmSuccessCount + llmFailCount)) * 100
        } else 0.0

        logInfo(s"Progress: ${llmSuccessCount + llmFailCount} chunks processed")
        logInfo(s"  Success: $llmSuccessCount (${successRate.toInt}%)")
        logInfo(s"  Failed: $llmFailCount")
        logInfo(s"  Skipped short: $skippedShortChunks")
        logInfo(s"  Total concepts: $conceptCount")
      }

    } catch {
      case e: Exception =>
        logError(s"Fatal error in ConceptStage for chunk ${chunk.chunkId}: ${e.getMessage}", e)
    }
  }

  override def close(): Unit = {
    super.close()
    logInfo("=" * 60)
    logInfo("ConceptStage Final Statistics:")
    logInfo(s"  Total concepts extracted: $conceptCount")
    logInfo(s"  LLM successes: $llmSuccessCount")
    logInfo(s"  LLM failures: $llmFailCount")
    logInfo(s"  Skipped short chunks: $skippedShortChunks")
    if (llmSuccessCount + llmFailCount > 0) {
      val successRate = (llmSuccessCount.toDouble / (llmSuccessCount + llmFailCount)) * 100
      logInfo(s"  Success rate: ${successRate.toInt}%")
    }
    logInfo("=" * 60)
  }
}

object ConceptStage {
  def apply(config: OllamaConfig): ConceptStage = new ConceptStage(config)
}