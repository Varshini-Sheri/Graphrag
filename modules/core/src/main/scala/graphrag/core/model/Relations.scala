package graphrag.core.model
import scala.collection.mutable
/**
 * Relation types and models for concept relationships.
 */

/**
 * Represents a mention of a concept within a chunk
 */
case class Mentions(chunkId: String, concept: Concept) {

  def toEdge: UpsertEdge = UpsertEdge(
    fromLabel = "Chunk",
    fromId = chunkId,
    rel = "MENTIONS",
    toLabel = "Concept",
    toId = concept.conceptId,
    props = Map("surface" -> concept.surface)
  )

  // For co-occurrence extraction (Java API version)
  var seenConcepts: mutable.ListBuffer[Concept] =
    mutable.ListBuffer.empty[Concept]
}

/**
 * Represents co-occurrence of two concepts within a window
 */
case class CoOccur(a: Concept, b: Concept, windowId: String, freq: Long) {

  /**
   * Get ordered pair key for deduplication
   */
  def pairKey: String = {
    if (a.conceptId < b.conceptId) s"${a.conceptId}:${b.conceptId}"
    else s"${b.conceptId}:${a.conceptId}"
  }

  /**
   * Get ordered concepts (alphabetically by ID)
   */
  def ordered: (Concept, Concept) = {
    if (a.conceptId < b.conceptId) (a, b) else (b, a)
  }

  /**
   * Merge with another co-occurrence (sum frequencies)
   */
  def merge(other: CoOccur): CoOccur = {
    require(pairKey == other.pairKey, "Cannot merge different concept pairs")
    copy(freq = freq + other.freq)
  }

  def toEdge: UpsertEdge = {
    val (first, second) = ordered
    UpsertEdge(
      fromLabel = "Concept",
      fromId = first.conceptId,
      rel = "CO_OCCURS",
      toLabel = "Concept",
      toId = second.conceptId,
      props = Map("windowId" -> windowId, "freq" -> freq)
    )
  }
}

object CoOccur {

  /**
   * Create an ordered co-occurrence
   */
  def create(a: Concept, b: Concept, windowId: String): CoOccur = {
    val (first, second) = if (a.conceptId < b.conceptId) (a, b) else (b, a)
    CoOccur(first, second, windowId, 1L)
  }
}

/**
 * Represents a candidate relation to be scored by LLM
 */
case class RelationCandidate(a: Concept, b: Concept, evidence: String) {

  /**
   * Get ordered pair key
   */
  def pairKey: String = {
    if (a.conceptId < b.conceptId) s"${a.conceptId}:${b.conceptId}"
    else s"${b.conceptId}:${a.conceptId}"
  }

  /**
   * Create a description for prompting
   */
  def description: String =
    s"${a.surface} <-> ${b.surface}: $evidence"
}

/**
 * Represents a scored/verified relation from LLM
 */
case class ScoredRelation(
                           a: Concept,
                           predicate: String,
                           b: Concept,
                           confidence: Double,
                           evidence: String
                         ) {

  /**
   * Check if this relation meets a confidence threshold
   */
  def meetsThreshold(minConfidence: Double): Boolean =
    confidence >= minConfidence

  /**
   * Get triple representation
   */
  def triple: (String, String, String) =
    (a.lemma, predicate, b.lemma)

  /**
   * Get human-readable representation
   */
  def readable: String =
    s"${a.surface} --[$predicate]--> ${b.surface} (${(confidence * 100).toInt}%)"

  def toEdge: UpsertEdge = UpsertEdge(
    fromLabel = "Concept",
    fromId = a.conceptId,
    rel = "RELATES_TO",
    toLabel = "Concept",
    toId = b.conceptId,
    props = Map(
      "predicate" -> predicate,
      "confidence" -> confidence,
      "evidence" -> evidence
    )
  )
}

/**
 * Represents an LLM verdict on a relation candidate
 */
case class LlmVerdict(
                       predicate: String,
                       confidence: Double,
                       evidence: String,
                       ref: String
                     ) {

  /**
   * Check if this verdict is valid
   */
  def isValid: Boolean =
    predicate.nonEmpty && confidence >= 0.0 && confidence <= 1.0

  /**
   * Normalize predicate to standard form
   */
  def normalized: LlmVerdict = copy(
    predicate = predicate.toLowerCase.trim.replace(" ", "_")
  )
}

object LlmVerdict {

  /**
   * Default verdict for fallback
   */
  def default(evidence: String): LlmVerdict = LlmVerdict(
    predicate = "related_to",
    confidence = 0.5,
    evidence = evidence,
    ref = "default"
  )

  /**
   * Create a high-confidence verdict
   */
  def confident(predicate: String, evidence: String): LlmVerdict = LlmVerdict(
    predicate = predicate,
    confidence = 0.9,
    evidence = evidence,
    ref = "manual"
  )
}

/**
 * Valid relation predicates
 */
object Predicates {
  val IS_A = "is_a"
  val PART_OF = "part_of"
  val CAUSES = "causes"
  val CAUSED_BY = "caused_by"
  val SYNONYM_OF = "synonym_of"
  val RELATED_TO = "related_to"
  val USES = "uses"
  val USED_BY = "used_by"
  val IMPROVES = "improves"
  val DEPENDS_ON = "depends_on"
  val CONTAINS = "contains"
  val BELONGS_TO = "belongs_to"
  val ENABLES = "enables"
  val PREVENTS = "prevents"

  val All: Set[String] = Set(
    IS_A, PART_OF, CAUSES, CAUSED_BY, SYNONYM_OF, RELATED_TO,
    USES, USED_BY, IMPROVES, DEPENDS_ON, CONTAINS, BELONGS_TO,
    ENABLES, PREVENTS
  )

  def isValid(predicate: String): Boolean =
    All.contains(predicate.toLowerCase)

  def normalize(predicate: String): String =
    predicate.toLowerCase.trim.replace(" ", "_")
}