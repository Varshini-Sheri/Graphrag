package graphrag.core.utils

import java.util.UUID
import java.time.Instant

/**
 * Utility functions for ID generation.
 */
object IdUtils {

  /**
   * Generate a random UUID
   */
  def uuid(): String = UUID.randomUUID().toString

  /**
   * Generate a short UUID (first 8 chars)
   */
  def shortUuid(): String = uuid().take(8)

  /**
   * Generate a timestamp-based ID
   */
  def timestampId(): String = {
    val now = Instant.now()
    s"${now.getEpochSecond}_${shortUuid()}"
  }

  /**
   * Generate a prefixed ID
   */
  def prefixedId(prefix: String): String = s"${prefix}_${shortUuid()}"

  /**
   * Generate a chunk ID from components
   */
  def chunkId(docId: String, spanStart: Int, spanEnd: Int): String = {
    HashUtils.sha256Short(s"$docId:$spanStart:$spanEnd")
  }

  /**
   * Generate a concept ID from lemma
   */
  def conceptId(lemma: String): String = {
    val normalized = lemma.toLowerCase.trim.replaceAll("\\s+", "_")
    s"c_${HashUtils.sha256Short(normalized)}"
  }

  /**
   * Generate a relation ID from concept pair
   */
  def relationId(conceptA: String, conceptB: String, predicate: String): String = {
    val ordered = if (conceptA < conceptB) s"$conceptA:$conceptB" else s"$conceptB:$conceptA"
    s"r_${HashUtils.sha256Short(s"$ordered:$predicate")}"
  }

  /**
   * Generate a document ID from URI
   */
  def docId(uri: String): String = {
    s"doc_${HashUtils.sha256Short(uri)}"
  }

  /**
   * Validate UUID format
   */
  def isValidUuid(id: String): Boolean = {
    try {
      UUID.fromString(id)
      true
    } catch {
      case _: IllegalArgumentException => false
    }
  }
}