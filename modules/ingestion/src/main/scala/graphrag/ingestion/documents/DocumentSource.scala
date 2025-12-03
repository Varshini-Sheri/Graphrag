package graphrag.ingestion.documents

import org.apache.flink.streaming.api.functions.source.SourceFunction
import io.circe.parser._
import io.circe.generic.auto._
import graphrag.core.utils.Logging

import scala.io.Source
import java.io.File

/**
 * Reads documents.jsonl and converts DocumentJson -> Document.
 * We keep year = None and let metadata extractors infer it.
 */
class DocumentSource(path: String) extends SourceFunction[Document] with Logging {

  @volatile private var running = true

  override def run(ctx: SourceFunction.SourceContext[Document]): Unit = {
    val file = new File(path)
    if (!file.exists()) {
      logError(s"DocumentSource: file does not exist: $path")
      return
    }

    val src = Source.fromFile(file, "UTF-8")

    try {
      for (line <- src.getLines() if running) {
        val clean = line.trim
        if (clean.nonEmpty) {
          decode[DocumentJson](clean) match {
            case Right(raw) =>
              // Convert JSON -> runtime Document model
              val doc = Document(
                docId = raw.docId,
                text  = raw.text,
                uri   = raw.uri,
                year  = None            // inferred later from text/uri
              )
              ctx.collect(doc)

            case Left(err) =>
              logWarn(s"Document decode error: ${err.getMessage}")
          }
        }
      }
    } finally {
      src.close()
    }
  }

  override def cancel(): Unit = {
    running = false
  }
}

object DocumentSource {
  def fromPath(p: String): DocumentSource = new DocumentSource(p)
}
