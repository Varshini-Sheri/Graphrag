package graphrag.job

import graphrag.core.model.{GraphWrite, UpsertNode, UpsertEdge}
import graphrag.ingestion.documents.Document

object DocumentProjector {

  def toPaperNode(doc: Document): GraphWrite = {
    UpsertNode(
      label = "Paper",
      id = doc.docId,
      props = Map(
        "title" -> doc.text.split("\n").headOption.getOrElse("Unknown Title"),
        "uri"   -> doc.uri,
        "year"  -> doc.year.getOrElse(0)
      )
    )
  }
}
