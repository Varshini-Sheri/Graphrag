package graphrag.ingestion.relations

import graphrag.core.model.{Concept, RelationCandidate}
import graphrag.core.utils.Logging

/**
 * Extracts relation patterns from text using regex and heuristics.
 * Identifies syntactic patterns that suggest semantic relationships.
 */
object PatternExtractor extends Logging {

  // Relation patterns: (pattern, predicate)
  private val RelationPatterns: Seq[(String, String)] = Seq(
    // is-a patterns
    ("""(?i)(\w+)\s+is\s+a\s+(?:type\s+of\s+)?(\w+)""", "is_a"),
    ("""(?i)(\w+)\s+(?:is|are)\s+(?:an?\s+)?(\w+)""", "is_a"),

    // part-of patterns
    ("""(?i)(\w+)\s+(?:is\s+)?part\s+of\s+(\w+)""", "part_of"),
    ("""(?i)(\w+)\s+contains?\s+(\w+)""", "contains"),
    ("""(?i)(\w+)\s+includes?\s+(\w+)""", "contains"),

    // causal patterns
    ("""(?i)(\w+)\s+causes?\s+(\w+)""", "causes"),
    ("""(?i)(\w+)\s+leads?\s+to\s+(\w+)""", "causes"),
    ("""(?i)(\w+)\s+results?\s+in\s+(\w+)""", "causes"),

    // usage patterns
    ("""(?i)(\w+)\s+uses?\s+(\w+)""", "uses"),
    ("""(?i)(\w+)\s+(?:is\s+)?based\s+on\s+(\w+)""", "uses"),
    ("""(?i)(\w+)\s+employs?\s+(\w+)""", "uses"),
    ("""(?i)(\w+)\s+leverages?\s+(\w+)""", "uses"),

    // improvement patterns
    ("""(?i)(\w+)\s+improves?\s+(\w+)""", "improves"),
    ("""(?i)(\w+)\s+enhances?\s+(\w+)""", "improves"),
    ("""(?i)(\w+)\s+optimizes?\s+(\w+)""", "improves"),

    // dependency patterns
    ("""(?i)(\w+)\s+depends?\s+on\s+(\w+)""", "depends_on"),
    ("""(?i)(\w+)\s+requires?\s+(\w+)""", "depends_on"),

    // comparison patterns
    ("""(?i)(\w+)\s+(?:is\s+)?similar\s+to\s+(\w+)""", "similar_to"),
    ("""(?i)(\w+)\s+(?:is\s+)?(?:the\s+)?same\s+as\s+(\w+)""", "synonym_of"),

    // enablement patterns
    ("""(?i)(\w+)\s+enables?\s+(\w+)""", "enables"),
    ("""(?i)(\w+)\s+allows?\s+(\w+)""", "enables"),
    ("""(?i)(\w+)\s+supports?\s+(\w+)""", "enables")
  )

  /**
   * Extract relation patterns from text
   */
  def extractPatterns(text: String): Seq[String] = {
    RelationPatterns.flatMap { case (pattern, predicate) =>
      pattern.r.findAllMatchIn(text).map { m =>
        s"${m.group(1)} --[$predicate]--> ${m.group(2)}"
      }
    }
  }

  /**
   * Extract relation candidates from text given known concepts
   */
  def extractCandidates(text: String, concepts: Seq[Concept]): Seq[RelationCandidate] = {
    val conceptMap = concepts.map(c => c.lemma.toLowerCase -> c).toMap
    val candidates = scala.collection.mutable.ListBuffer[RelationCandidate]()

    RelationPatterns.foreach { case (pattern, predicate) =>
      pattern.r.findAllMatchIn(text).foreach { m =>
        val termA = m.group(1).toLowerCase
        val termB = m.group(2).toLowerCase

        // Check if both terms match known concepts
        for {
          conceptA <- conceptMap.get(termA).orElse(findConceptByPrefix(termA, conceptMap))
          conceptB <- conceptMap.get(termB).orElse(findConceptByPrefix(termB, conceptMap))
          if conceptA.conceptId != conceptB.conceptId
        } {
          candidates += RelationCandidate(
            a = conceptA,
            b = conceptB,
            evidence = s"Pattern '$predicate' matched: ${m.matched}"
          )
        }
      }
    }

    candidates.toSeq.distinct
  }

  /**
   * Find concept by prefix match
   */
  private def findConceptByPrefix(term: String, conceptMap: Map[String, Concept]): Option[Concept] = {
    conceptMap.find { case (lemma, _) =>
      lemma.startsWith(term) || term.startsWith(lemma)
    }.map(_._2)
  }

  /**
   * Infer predicate between two concepts based on their properties
   */
  def inferPredicate(conceptA: Concept, conceptB: Concept): String = {
    val a = conceptA.lemma.toLowerCase
    val b = conceptB.lemma.toLowerCase

    if (a.contains(b) || b.contains(a)) "related_to"
    else if (conceptA.origin == "ACRONYM" && conceptB.origin != "ACRONYM") "abbreviation_of"
    else if (a.endsWith("ing") || a.endsWith("tion")) "process_of"
    else "related_to"
  }
}