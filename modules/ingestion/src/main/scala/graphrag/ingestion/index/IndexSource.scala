package graphrag.ingestion.index

import graphrag.core.model.Chunk
import graphrag.core.utils.Logging
import io.circe.generic.auto._
import io.circe.parser.decode
import org.apache.flink.streaming.api.functions.source.SourceFunction

import scala.io.Source
import java.io.File



class IndexSource(path: String)
  extends SourceFunction[Chunk] with Logging {

  @volatile private var running = true

  override def run(ctx: SourceFunction.SourceContext[Chunk]): Unit = {

    val file = new File(path)
    if (!file.exists()) {
      logError(s"IndexSource: Missing file $path")
      return
    }

    val src = Source.fromFile(path, "UTF-8")

    for (raw <- src.getLines() if running) {
      decode[ChunkJson](raw) match {
        case Right(cj) =>
          ctx.collect(
            Chunk(
              chunkId = cj.chunkId,
              docId = cj.docId,
              span = cj.span,
              text = cj.text,
              sourceUri = cj.sourceUri,
              hash = cj.hash
            )
          )

        case Left(err) =>
          logWarn(s"IndexSource decode failed: $err")
      }
    }

    src.close()
  }

  override def cancel(): Unit = running = false
}

object IndexSource {
  def fromPath(path: String): IndexSource =
    new IndexSource(path)
}
