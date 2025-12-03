package graphrag.ingestion.normalize

import graphrag.core.model.Chunk
import graphrag.core.utils.Logging

object Normalize extends Logging {

  /**
   * Clean and normalize a chunk
   */
  def cleanAndTag(chunk: Chunk): Chunk = {
    val cleanedText = chunk.text
      .replaceAll("\\s+", " ")           // Collapse whitespace
      .replaceAll("[^\\p{Print}]", "")   // Remove non-printable
      .trim

    chunk.copy(text = cleanedText)
  }
}