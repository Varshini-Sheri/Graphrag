package graphrag.ingestion.metadata

import graphrag.core.model.{Chunk, GraphWrite}
import graphrag.ingestion.documents.Document
import graphrag.core.utils.Logging

/**
 * Extracts metadata from documents and chunks.
 * Returns GraphWrite operations but does NOT contain construction logic.
 * All graph construction delegated to GraphWrite object.
 */
object MetadataExtractors extends Logging {

  // ======================================================================
  // TEXT EXTRACTION HELPERS (Pure functions)
  // ======================================================================

  /**
   * Extract title from document text.
   * Takes first non-empty line that looks like a title.
   */
  def extractTitle(text: String): Option[String] = {
    val lines = text.split("\\r?\\n")
      .map(_.trim)
      .filter(_.nonEmpty)
      .filterNot(_.matches(".*@.*"))                            // skip emails
      .filterNot(_.matches(".*ACM|IEEE|Conference|Workshop.*")) // skip headers
      .filter(_.length >= 5)

    lines.headOption
  }

  /**
   * Extract year from text or URI.
   * Looks for 4-digit year pattern (1900-2099).
   */
  def extractYear(text: String, uri: String): Option[Int] = {
    val yearRegex = "(19\\d{2}|20\\d{2})".r

    yearRegex
      .findFirstIn(uri)
      .orElse(yearRegex.findFirstIn(text))
      .flatMap(y => scala.util.Try(y.toInt).toOption)
  }

  /**
   * Extract venue from text (ACM, IEEE conferences).
   */
  def extractVenue(text: String): Option[String] = {
    val venueRegex = "(ACM|IEEE)\\s+[A-Z]+\\s*\\d{4}".r
    venueRegex.findFirstIn(text)
  }

  // ======================================================================
  // CHUNK-LEVEL METADATA EXTRACTION
  // ======================================================================

  /**
   * Extract metadata from a single chunk.
   * For now, just returns BELONGS_TO edge linking chunk to paper.
   */
  def fromChunk(chunk: Chunk): Seq[GraphWrite] = {
    Seq(
      GraphWrite.belongsToEdge(
        chunkId = chunk.chunkId,
        docId   = chunk.docId
      )
    )
  }

  // ======================================================================
  // DOCUMENT-LEVEL METADATA EXTRACTION
  // ======================================================================

  /**
   * Extract Paper node from Document.
   * Uses docId as the stable Paper ID.
   */
  def fromDocument(doc: Document): Seq[GraphWrite] = {
    val title = extractTitle(doc.text)
    val year  = doc.year.orElse(extractYear(doc.text, doc.uri))
    val venue = extractVenue(doc.text)

    Seq(
      GraphWrite.paperNode(
        docId   = doc.docId,
        title   = title,
        year    = year,
        venue   = venue,
        authors = None  // Could extract from text if needed
      )
    )
  }

  /**
   * Convenience method for pipeline - returns single Paper node.
   */
  def paperFromDocument(doc: Document): GraphWrite = {
    val title = extractTitle(doc.text)
    val year  = doc.year.orElse(extractYear(doc.text, doc.uri))
    val venue = extractVenue(doc.text)

    GraphWrite.paperNode(
      docId   = doc.docId,
      title   = title,
      year    = year,
      venue   = venue
    )
  }

  // ======================================================================
  // RESEARCH GRAPH EXTRACTION (Tasks, Datasets, Techniques, etc.)
  // ======================================================================

  /**
   * Extract Task mentions from text.
   * TODO: Implement NLP-based extraction or keyword matching.
   */
  def extractTasks(text: String, docId: String): Seq[GraphWrite] = {
    // Placeholder - implement your task extraction logic
    val taskKeywords = Seq("defect prediction", "bug prediction", "JIT")

    taskKeywords
      .filter(kw => text.toLowerCase.contains(kw.toLowerCase))
      .map { taskName =>
        val taskId = taskName.toLowerCase.replace(" ", "_")
        Seq(
          GraphWrite.taskNode(taskId, taskName),
          GraphWrite.addressesEdge(docId, taskId)
        )
      }
      .flatten
  }

  /**
   * Extract Dataset mentions from text.
   */
  def extractDatasets(text: String, docId: String): Seq[GraphWrite] = {
    val datasetKeywords = Seq(
      "JITGIT", "SEOSS-JIT", "CommitGuru",
      "Defects4J", "BugsJS", "GitHub"
    )

    datasetKeywords
      .filter(ds => text.contains(ds))
      .map { dsName =>
        val dsId = dsName.toLowerCase.replace(" ", "_")
        Seq(
          GraphWrite.datasetNode(dsId, dsName),
          GraphWrite.usesDatasetEdge(docId, dsId)
        )
      }
      .flatten
  }

  /**
   * Extract Technique mentions from text.
   */
  def extractTechniques(text: String, docId: String): Seq[GraphWrite] = {
    val techKeywords = Map(
      "random forest" -> "ensemble",
      "neural network" -> "deep_learning",
      "CNN" -> "deep_learning",
      "LSTM" -> "deep_learning",
      "transformer" -> "deep_learning",
      "gradient boosting" -> "ensemble"
    )

    techKeywords
      .filter { case (tech, _) => text.toLowerCase.contains(tech.toLowerCase) }
      .map { case (tech, family) =>
        val techId = tech.toLowerCase.replace(" ", "_")
        Seq(
          GraphWrite.techniqueNode(techId, tech, Some(family)),
          GraphWrite.proposesEdge(docId, techId)
        )
      }
      .flatten
      .toSeq
  }

  /**
   * Extract Metric mentions from text.
   */
  def extractMetrics(text: String, docId: String): Seq[GraphWrite] = {
    val metricKeywords = Seq("AUC", "F1", "precision", "recall", "MCC", "accuracy")

    metricKeywords
      .filter(m => text.matches(s".*\\b$m\\b.*"))
      .map { metric =>
        val metricId = metric.toLowerCase
        Seq(
          GraphWrite.metricNode(metricId, metric),
          GraphWrite.reportsEdge(docId, metricId)
        )
      }
      .flatten
  }

  /**
   * Extract all research metadata from text.
   * Combines Tasks, Datasets, Techniques, Metrics extraction.
   */
  def extractResearchMetadata(text: String, docId: String): Seq[GraphWrite] = {
    extractTasks(text, docId) ++
      extractDatasets(text, docId) ++
      extractTechniques(text, docId) ++
      extractMetrics(text, docId)
  }
}