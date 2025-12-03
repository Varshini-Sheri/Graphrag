package graphrag.core.model

/** Base type for all graph write operations to Neo4j. */
sealed trait GraphWrite

/** Create or update a node. */
final case class UpsertNode(
                             label: String,
                             id: String,
                             props: Map[String, Any]
                           ) extends GraphWrite

/** Create or update a relationship. */
final case class UpsertEdge(
                             fromLabel: String,
                             fromId: String,
                             rel: String,
                             toLabel: String,
                             toId: String,
                             props: Map[String, Any]
                           ) extends GraphWrite

/**
 * Convenience constructors for all graph write patterns.
 * Centralizes all node and edge construction logic.
 */
object GraphWrite {

  // ======================================================================
  // ORIGINAL 3 NODE TYPES (Chunk, Concept)
  // ======================================================================

  def chunkNode(
                 chunkId: String,
                 docId: String,
                 text: String,
                 spanStart: Int,
                 spanEnd: Int,
                 sourceUri: String,
                 hash: String
               ): GraphWrite =
    UpsertNode(
      label = "Chunk",
      id    = chunkId,
      props = Map(
        "chunkId"   -> chunkId,
        "docId"     -> docId,
        "text"      -> text,
        "spanStart" -> spanStart,
        "spanEnd"   -> spanEnd,
        "sourceUri" -> sourceUri,
        "hash"      -> hash
      )
    )

  def conceptNode(
                   conceptId: String,
                   lemma: String,
                   surface: String,
                   origin: String,
                   name: String  // ADD THIS
                 ): GraphWrite =
    UpsertNode(
      label = "Concept",
      id    = conceptId,
      props = Map(
        "conceptId" -> conceptId,
        "lemma"   -> lemma,
        "surface" -> surface,
        "origin"  -> origin,
        "name"    -> name  // ADD THIS
      )
    )

  // ======================================================================
  // RESEARCH GRAPH NODE TYPES
  // ======================================================================

  def paperNode(
                 docId: String,
                 title: Option[String] = None,
                 year: Option[Int] = None,
                 venue: Option[String] = None,
                 authors: Option[String] = None
               ): GraphWrite = {
    val baseProps: Map[String, Any] = Map("docId" -> docId)

    val withOptionals = baseProps ++
      title.map("title" -> _) ++
      year.map("year" -> _) ++
      venue.map("venue" -> _) ++
      authors.map("authors" -> _)

    UpsertNode(
      label = "Paper",
      id    = docId,
      props = withOptionals
    )
  }

  def taskNode(
                taskId: String,
                name: String,
                description: Option[String] = None
              ): GraphWrite =
    UpsertNode(
      label = "Task",
      id    = taskId,
      props = Map(
        "taskId" -> taskId,
        "name" -> name
      ) ++ description.map("description" -> _)
    )

  def datasetNode(
                   datasetId: String,
                   name: String,
                   description: Option[String] = None
                 ): GraphWrite =
    UpsertNode(
      label = "Dataset",
      id    = datasetId,
      props = Map(
        "datasetId" -> datasetId,
        "name" -> name
      ) ++ description.map("description" -> _)
    )

  def techniqueNode(
                     techId: String,
                     name: String,
                     family: Option[String] = None
                   ): GraphWrite =
    UpsertNode(
      label = "Technique",
      id    = techId,
      props = Map(
        "techId" -> techId,
        "name" -> name
      ) ++ family.map("family" -> _)
    )

  def metricNode(
                  metricId: String,
                  name: String
                ): GraphWrite =
    UpsertNode(
      label = "Metric",
      id    = metricId,
      props = Map(
        "metricId" -> metricId,
        "name" -> name
      )
    )

  def baselineNode(
                    baselineId: String,
                    name: String,
                    description: Option[String] = None
                  ): GraphWrite =
    UpsertNode(
      label = "Baseline",
      id    = baselineId,
      props = Map(
        "baselineId" -> baselineId,
        "name" -> name
      ) ++ description.map("description" -> _)
    )

  def snippetNode(
                   snippetId: String,
                   text: String,
                   chunkId: Option[String] = None,
                   confidence: Option[Double] = None
                 ): GraphWrite =
    UpsertNode(
      label = "Snippet",
      id    = snippetId,
      props = Map(
        "snippetId" -> snippetId,
        "text" -> text
      ) ++ chunkId.map("chunkId" -> _) ++ confidence.map("confidence" -> _)
    )

  // ======================================================================
  // ORIGINAL 3 EDGE TYPES (MENTIONS, CO_OCCURS, RELATES_TO)
  // ======================================================================

  def mentionsEdge(
                    chunkId: String,
                    conceptId: String,
                    surface: String
                  ): GraphWrite =
    UpsertEdge(
      fromLabel = "Chunk",
      fromId    = chunkId,
      rel       = "MENTIONS",
      toLabel   = "Concept",
      toId      = conceptId,
      props     = Map("surface" -> surface)
    )

  def coOccursEdge(
                    aConceptId: String,
                    bConceptId: String,
                    windowId: String,
                    freq: Long
                  ): GraphWrite =
    UpsertEdge(
      fromLabel = "Concept",
      fromId    = aConceptId,
      rel       = "CO_OCCURS",
      toLabel   = "Concept",
      toId      = bConceptId,
      props     = Map(
        "windowId" -> windowId,
        "freq"     -> freq
      )
    )

  def relatesEdge(
                   aConceptId: String,
                   bConceptId: String,
                   predicate: String,
                   confidence: Double,
                   evidence: String
                 ): GraphWrite =
    UpsertEdge(
      fromLabel = "Concept",
      fromId    = aConceptId,
      rel       = "RELATES_TO",
      toLabel   = "Concept",
      toId      = bConceptId,
      props     = Map(
        "predicate"  -> predicate,
        "confidence" -> confidence,
        "evidence"   -> evidence
      )
    )

  // ======================================================================
  // RESEARCH GRAPH EDGE TYPES
  // ======================================================================

  def belongsToEdge(
                     chunkId: String,
                     docId: String
                   ): GraphWrite =
    UpsertEdge(
      fromLabel = "Chunk",
      fromId    = chunkId,
      rel       = "BELONGS_TO",
      toLabel   = "Paper",
      toId      = docId,
      props     = Map.empty[String, Any]
    )

  def addressesEdge(
                     docId: String,
                     taskId: String
                   ): GraphWrite =
    UpsertEdge(
      fromLabel = "Paper",
      fromId    = docId,
      rel       = "ADDRESSES",
      toLabel   = "Task",
      toId      = taskId,
      props     = Map.empty[String, Any]
    )

  def usesDatasetEdge(
                       docId: String,
                       datasetId: String
                     ): GraphWrite =
    UpsertEdge(
      fromLabel = "Paper",
      fromId    = docId,
      rel       = "USES_DATASET",
      toLabel   = "Dataset",
      toId      = datasetId,
      props     = Map.empty[String, Any]
    )

  def proposesEdge(
                    docId: String,
                    techId: String
                  ): GraphWrite =
    UpsertEdge(
      fromLabel = "Paper",
      fromId    = docId,
      rel       = "PROPOSES",
      toLabel   = "Technique",
      toId      = techId,
      props     = Map.empty[String, Any]
    )

  def reportsEdge(
                   docId: String,
                   metricId: String,
                   value: Option[Double] = None
                 ): GraphWrite =
    UpsertEdge(
      fromLabel = "Paper",
      fromId    = docId,
      rel       = "REPORTS",
      toLabel   = "Metric",
      toId      = metricId,
      props     = value.map("value" -> _).toMap
    )

  def improvesOverEdge(
                        docId: String,
                        baselineId: String,
                        delta: Option[Double] = None,
                        metric: Option[String] = None
                      ): GraphWrite =
    UpsertEdge(
      fromLabel = "Paper",
      fromId    = docId,
      rel       = "IMPROVES_OVER",
      toLabel   = "Baseline",
      toId      = baselineId,
      props     = delta.map("delta" -> _).toMap ++ metric.map("metric" -> _).toMap
    )

  def evidenceEdge(
                    conceptId: String,
                    snippetId: String,
                    role: String = "source"
                  ): GraphWrite =
    UpsertEdge(
      fromLabel = "Concept",
      fromId    = conceptId,
      rel       = "EVIDENCE",
      toLabel   = "Snippet",
      toId      = snippetId,
      props     = Map("role" -> role)
    )
}