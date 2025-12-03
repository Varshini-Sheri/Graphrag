package graphrag.ingestion.index

/**
 * JSON structure exactly matching chunks.jsonl.
 * Parsed from disk, then converted to Chunk (core model).
 */
case class ChunkJson(
                      chunkId: String,
                      docId: String,
                      span: (Int, Int),
                      text: String,
                      sourceUri: String,
                      hash: String
                    )
