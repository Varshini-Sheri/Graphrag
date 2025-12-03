package graphrag.ingestion.concept

import graphrag.core.model.{Chunk, Concept, Mentions}
import graphrag.core.config.OllamaConfig
import graphrag.core.utils.Logging
import graphrag.llm.{OllamaClient, LlmPromptTemplates}

import scala.util.{Try, Success, Failure}
import io.circe.parser._
import io.circe.generic.auto._

/**
 * LLM-based concept extraction with quality filtering.
 */
object LlmConceptExtractor extends Logging {

  case class LlmConcept(surface: String, lemma: String, `type`: String)

  // Aggressive stopword/garbage filtering
  private val StopWords = Set(
    "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
    "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
    "be", "have", "has", "had", "do", "does", "did", "will", "would", "could",
    "should", "may", "might", "must", "shall", "can", "need", "this", "that",
    "these", "those", "it", "its", "they", "them", "their", "we", "our", "you",
    "your", "i", "me", "my", "he", "she", "his", "her", "also", "however",
    "thus", "therefore", "hence", "such", "which", "what", "when", "where",
    "how", "who", "whom", "whose", "ii", "iii", "iv", "overall", "then",
    "than", "some", "all", "each", "every", "both", "few", "more", "most",
    "other", "into", "through", "during", "before", "after", "above", "below",
    "between", "under", "again", "further", "once", "here", "there", "while",
    "only", "very", "too", "can", "just", "even", "any", "not", "no", "nor",
    "so", "than", "too"
  )

  // Common author name patterns (first names to reject)
  private val CommonFirstNames = Set(
    "john", "james", "michael", "robert", "david", "william", "richard", "charles",
    "joseph", "thomas", "christopher", "daniel", "paul", "mark", "donald", "george",
    "kenneth", "steven", "edward", "brian", "ronald", "anthony", "kevin", "jason",
    "matthew", "gary", "timothy", "jose", "larry", "jeffrey", "frank", "scott",
    "eric", "stephen", "andrew", "raymond", "gregory", "joshua", "jerry", "dennis",
    "mary", "patricia", "jennifer", "linda", "barbara", "elizabeth", "susan",
    "jessica", "sarah", "karen", "nancy", "lisa", "betty", "margaret", "sandra",
    "ashley", "kimberly", "emily", "donna", "michelle", "dorothy", "carol", "amanda"
  )

  /**
   * Extract concepts using LLM with quality filtering
   */
  def extract(chunk: Chunk, client: OllamaClient): Seq[Concept] = {
    try {
      // Skip very short chunks
      if (chunk.text.length < 100) {
        return Seq.empty
      }

      // ADD THIS LINE - Unique marker
      logInfo("🔵 LlmConceptExtractor.extract() CALLED - IMPROVED VERSION v2.0")

      val textSample = chunk.text.take(500)
      val prompt = LlmPromptTemplates.conceptExtractionPrompt(textSample)

      // ADD THIS LINE - Show first 200 chars of prompt
      logInfo(s"🔵 Prompt first 200 chars: ${prompt.take(200)}")

      logDebug(s"Extracting concepts from chunk ${chunk.chunkId}")

      client.complete(prompt) match {
        case Some(response) =>
          parseConceptsFromJson(response) match {
            case Success(llmConcepts) =>
              // Filter and convert
              val validConcepts = llmConcepts
                .filter(isValidConcept)
                .map(toConcept)

              logInfo(s"LLM extracted ${llmConcepts.size} concepts, ${validConcepts.size} passed filters")
              validConcepts

            case Failure(e) =>
              logWarn(s"Failed to parse LLM response: ${e.getMessage}")
              logWarn(s"Response was: ${response.take(200)}")
              Seq.empty
          }

        case None =>
          logWarn(s"LLM returned no response for chunk ${chunk.chunkId}")
          Seq.empty
      }

    } catch {
      case e: Exception =>
        logError(s"LLM concept extraction failed: ${e.getMessage}")
        Seq.empty
    }
  }

  /**
   * Extract mentions from chunk
   */
  def extractMentions(chunk: Chunk, client: OllamaClient): Seq[Mentions] = {
    extract(chunk, client).map { concept =>
      Mentions(chunk.chunkId, concept)
    }
  }

  /**
   * Validate concept quality - reject garbage
   */
  private def isValidConcept(llmConcept: LlmConcept): Boolean = {
    val surface = llmConcept.surface.trim
    val lemma = llmConcept.lemma.trim.toLowerCase

    // Length checks
    if (surface.length < 3 || surface.length > 50) {
      logDebug(s"Rejected: invalid length '$surface'")
      return false
    }

    // Stopword check
    if (StopWords.contains(lemma)) {
      logDebug(s"Rejected: stopword '$surface'")
      return false
    }

    // Single letter or number
    if (surface.matches("^[A-Za-z0-9]$")) {
      logDebug(s"Rejected: single char '$surface'")
      return false
    }

    // Common first name (likely author)
    val words = surface.split("\\s+")
    if (words.length == 1 && CommonFirstNames.contains(lemma)) {
      logDebug(s"Rejected: common first name '$surface'")
      return false
    }

    // All stopwords (multi-word phrases)
    if (words.forall(w => StopWords.contains(w.toLowerCase))) {
      logDebug(s"Rejected: all stopwords '$surface'")
      return false
    }

    // Only digits
    if (surface.forall(_.isDigit)) {
      logDebug(s"Rejected: only digits '$surface'")
      return false
    }

    // Suspicious patterns
    if (surface.matches("^[^a-zA-Z]*$")) {  // No letters at all
      logDebug(s"Rejected: no letters '$surface'")
      return false
    }

    // Reject partial hyphenated terms (e.g., "crypto-API", "machine-learning")
    // These should be full terms like "cryptocurrency API" or standalone "API"
    if (surface.contains("-") && surface.split("-").length == 2) {
      val parts = surface.split("-")
      // If both parts are short and one ends with common suffixes, likely incomplete
      if (parts.exists(p => p.length < 4) ||
        surface.matches(".*-(API|based|driven|specific|level|scale)")) {
        logDebug(s"Rejected: partial hyphenated term '$surface'")
        return false
      }
    }

    // Reject single-word acronyms that are too short
    if (words.length == 1 && surface.length < 3 && surface.forall(_.isUpper)) {
      logDebug(s"Rejected: too-short acronym '$surface'")
      return false
    }

    // Require multi-word phrases OR well-known acronyms (3+ chars, all caps)
    val isMultiWord = words.length >= 2
    val isValidAcronym = surface.length >= 3 && surface.forall(c => c.isUpper || c.isDigit)

    if (!isMultiWord && !isValidAcronym) {
      logDebug(s"Rejected: not multi-word and not valid acronym '$surface'")
      return false
    }

    true
  }

  /**
   * Parse LLM JSON response
   */
  private def parseConceptsFromJson(response: String): Try[Seq[LlmConcept]] = Try {
    // Clean response - remove markdown code blocks
    val cleaned = response
      .replaceAll("```json\\s*", "")
      .replaceAll("```\\s*", "")
      .trim

    // Find JSON array
    val jsonArrayPattern = """(\[[\s\S]*\])""".r

    val jsonStr = jsonArrayPattern.findFirstIn(cleaned).getOrElse(cleaned)

    decode[List[LlmConcept]](jsonStr) match {
      case Right(concepts) => concepts
      case Left(error) =>
        logWarn(s"JSON parse error: ${error.getMessage}")
        List.empty
    }
  }

  /**
   * Convert LLM concept to our Concept model
   */
  private def toConcept(llmConcept: LlmConcept): Concept = {
    val normalized = llmConcept.lemma.toLowerCase
      .replaceAll("\\s+", "_")
      .replaceAll("[^a-z0-9_]", "")

    val conceptId = Concept.generateConceptId(normalized)

    Concept(
      conceptId = conceptId,
      lemma = normalized,
      surface = llmConcept.surface,
      origin = s"LLM_${llmConcept.`type`}",
      name = llmConcept.surface  // Use surface as display name
    )
  }
}
