package graphrag.llm

import io.circe.parser._
import io.circe.generic.auto._

/**
 * Represents an LLM's verdict on a relation between concepts.
 */
case class Verdict(
                    predicate: String,
                    confidence: Double,
                    evidence: String,
                    ref: String
                  ) {

  /**
   * Check if this verdict meets minimum confidence threshold
   */
  def meetsThreshold(minConfidence: Double): Boolean =
    confidence >= minConfidence

  /**
   * Normalize predicate to standard form
   */
  def normalized: Verdict = copy(
    predicate = predicate.toLowerCase.trim.replace(" ", "_")
  )
}

object Verdict {

  /**
   * Parse verdict from JSON string
   */
  def parse(json: String): Option[Verdict] = {
    decode[Verdict](json).toOption.map(_.normalized)
  }

  /**
   * Parse verdict from potentially messy LLM output
   */
  def parseFromLlmOutput(output: String): Option[Verdict] = {
    // Extract JSON object from LLM response
    val jsonPattern = """\{[^{}]*"predicate"[^{}]*\}""".r

    jsonPattern.findFirstIn(output).flatMap(parse)
  }

  /**
   * Create a default "related_to" verdict
   */
  def default(evidence: String): Verdict = Verdict(
    predicate = "related_to",
    confidence = 0.5,
    evidence = evidence,
    ref = "default"
  )

  /**
   * Create a high-confidence verdict
   */
  def confident(predicate: String, evidence: String): Verdict = Verdict(
    predicate = predicate,
    confidence = 0.9,
    evidence = evidence,
    ref = "manual"
  )
}