package graphrag.core.model

import graphrag.core.utils.HashUtils

/**
 * Represents a text chunk from a document.
 */
case class Chunk(
                  chunkId: String,
                  docId: String,
                  span: (Int, Int),
                  text: String,
                  sourceUri: String,
                  hash: String
                ) {

  def spanStart: Int = span._1
  def spanEnd: Int = span._2
  def length: Int = text.length
  def spanLength: Int = spanEnd - spanStart

  /** Extract just the filename from sourceUri */
  def sourceFileName: String = {
    val cleaned = sourceUri
      .replace("file://", "")
      .replace("file:/", "")
      .split("[/\\\\]").lastOption.getOrElse(sourceUri)
    cleaned
  }

  def overlaps(other: Chunk): Boolean =
    docId == other.docId && spanStart < other.spanEnd && spanEnd > other.spanStart

  def contains(position: Int): Boolean =
    position >= spanStart && position < spanEnd

  def preview(maxLength: Int = 100): String =
    if (text.length <= maxLength) text else text.take(maxLength) + "..."

  def toProps: Map[String, Any] = Map(
    "docId" -> docId,
    "text" -> text,
    "spanStart" -> spanStart,
    "spanEnd" -> spanEnd,
    "sourceUri" -> sourceUri,
    "hash" -> hash
  )
}

object Chunk {

  def generateChunkId(docId: String, span: (Int, Int), textHash: String): String =
    HashUtils.sha256Short(s"$docId:${span._1}:${span._2}:$textHash")

  def create(
              docId: String,
              span: (Int, Int),
              text: String,
              sourceUri: String
            ): Chunk = {
    val hash = HashUtils.contentHash(text)
    val chunkId = generateChunkId(docId, span, hash)
    Chunk(chunkId, docId, span, text, sourceUri, hash)
  }
}
