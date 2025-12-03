package graphrag.core.model

import graphrag.core.utils.HashUtils

/**
 * Represents a concept/entity extracted from text.
 * Concepts are the nodes in the knowledge graph.
 */
case class Concept(
                    conceptId: String,
                    lemma: String,
                    surface: String,
                    origin: String,
                    name: String  // Display name for Neo4j
                  ) {

  /**
   * Check if this concept matches another by lemma
   */
  def matches(other: Concept): Boolean =
    lemma.equalsIgnoreCase(other.lemma)

  /**
   * Check if this concept contains a term
   */
  def contains(term: String): Boolean =
    lemma.toLowerCase.contains(term.toLowerCase) ||
      surface.toLowerCase.contains(term.toLowerCase)

  override def toString: String =
    s"Concept($name, $lemma, $origin)"
}

object Concept {

  /**
   * Generate a deterministic concept ID from lemma
   */
  def generateConceptId(lemma: String): String = {
    val normalized = lemma.toLowerCase.trim.replaceAll("\\s+", "_")
    s"c_${HashUtils.sha256Short(normalized)}"
  }

  /**
   * Create a concept from surface form
   */
  def fromSurface(surface: String, origin: String = "UNKNOWN"): Concept = {
    val lemma = surface.toLowerCase.replaceAll("\\s+", "_").replaceAll("-", "_")
    Concept(
      conceptId = generateConceptId(lemma),
      lemma = lemma,
      surface = surface,
      origin = origin,
      name = surface  // Use surface as display name
    )
  }

  /**
   * Create a concept with explicit ID
   */
  def apply(lemma: String, surface: String, origin: String): Concept = {
    Concept(
      conceptId = generateConceptId(lemma),
      lemma = lemma,
      surface = surface,
      origin = origin,
      name = surface  // Use surface as display name
    )
  }

  /**
   * Merge two concepts (prefer first, combine origins)
   */
  def merge(a: Concept, b: Concept): Concept = {
    require(a.conceptId == b.conceptId, "Cannot merge concepts with different IDs")
    a.copy(
      origin = s"${a.origin},${b.origin}"
    )
  }
}