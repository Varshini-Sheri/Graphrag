package graphrag.ingestion.index

import graphrag.core.model.Chunk
import graphrag.core.utils.Logging
import org.apache.flink.streaming.api.functions.source.SourceFunction
import io.circe.parser._
import io.circe.generic.auto._

import scala.io.Source
import java.io.File

/**
 * Reads .jsonl files from local folder or single file.
 */
class LocalIndexSource(path: String) extends SourceFunction[Chunk] with Logging {

  @volatile private var isRunning = true

  override def run(ctx: SourceFunction.SourceContext[Chunk]): Unit = {
    val file = new File(path)

    if (!file.exists()) {
      logError(s"Path does not exist: $path")
      return
    }

    if (file.isDirectory)
      processDirectory(file, ctx)
    else
      processFile(file, ctx)
  }

  private def processDirectory(dir: File, ctx: SourceFunction.SourceContext[Chunk]): Unit = {
    val files = dir.listFiles()
      .filter(f => f.getName.endsWith(".jsonl") || f.getName.endsWith(".json"))
      .sortBy(_.getName)

    logInfo(s"LocalIndexSource: Found ${files.length} JSON files in ${dir.getPath}")

    files.foreach(f => if (isRunning) processFile(f, ctx))
  }

  private def processFile(file: File, ctx: SourceFunction.SourceContext[Chunk]): Unit = {
    logInfo(s"Processing file: ${file.getPath}")

    val src = Source.fromFile(file, "UTF-8")
    try {
      for (raw <- src.getLines() if isRunning) {
        val clean = raw.stripPrefix("\uFEFF").trim
        if (clean.nonEmpty) {
          decode[ChunkJson](clean) match {
            case Right(cj) =>
              ctx.collect(
                Chunk(
                  cj.chunkId,
                  cj.docId,
                  cj.span,
                  cj.text,
                  cj.sourceUri,
                  cj.hash
                )
              )
            case Left(err) =>
              logWarn(s"Failed to decode JSON: ${err.getMessage}")
          }
        }
      }
    } finally {
      src.close()
    }
  }

  override def cancel(): Unit = isRunning = false
}

object LocalIndexSource {
  def apply(path: String): LocalIndexSource = new LocalIndexSource(path)
}
