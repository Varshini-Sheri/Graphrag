package graphrag.llm

import graphrag.core.model.{RelationCandidate, LlmVerdict}
import graphrag.core.config.OllamaConfig
import graphrag.core.utils.Logging
import io.circe.parser._
import io.circe.generic.auto._
import scala.util.{Try, Success, Failure}

/**
 * Client for Ollama LLM API.
 * Handles HTTP communication, prompt construction, and response parsing.
 */
case class OllamaClient(config: OllamaConfig) extends Logging {

  import java.net.{HttpURLConnection, URL}
  import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter}

  /**
   * Score a relation candidate using the LLM
   */
  def scoreRelation(candidate: RelationCandidate): Option[LlmVerdict] = {
    val prompt = LlmPromptTemplates.relationExtractionPrompt(
      conceptA = s"${candidate.a.lemma} (${candidate.a.surface})",
      conceptB = s"${candidate.b.lemma} (${candidate.b.surface})",
      evidence = candidate.evidence
    )

    complete(prompt) match {
      case Some(response) =>
        parseVerdict(response) match {
          case Some(v) if isValidVerdict(v) => Some(v)
          case Some(v) =>
            logWarn(s"Invalid predicate '${v.predicate}', defaulting to related_to")
            Some(v.copy(predicate = "related_to"))
          case None =>
            logWarn("Failed to parse LLM response, using heuristic")
            Some(heuristicScore(candidate))
        }
      case None =>
        Some(heuristicScore(candidate))
    }
  }

  /**
   * Generate text completion (generic)
   * This is the main method used by both scoreRelation and LlmConceptExtractor
   */
  def complete(prompt: String): Option[String] = callOllama(prompt)

  /**
   * Call Ollama API
   */
  private def callOllama(prompt: String): Option[String] = {
    Try {
      val url = new URL(s"${config.endpoint}/api/generate")
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("POST")
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setDoOutput(true)
      conn.setConnectTimeout(config.timeoutMs)
      conn.setReadTimeout(config.timeoutMs)

      // Escape prompt for JSON
      val escapedPrompt = escapeJson(prompt)
      val requestBody =
        s"""{"model":"${config.model}","prompt":"$escapedPrompt","stream":false,"temperature":${config.temperature}}"""

      val writer = new OutputStreamWriter(conn.getOutputStream, "UTF-8")
      writer.write(requestBody)
      writer.flush()
      writer.close()

      val responseCode = conn.getResponseCode
      if (responseCode == 200) {
        val reader = new BufferedReader(new InputStreamReader(conn.getInputStream, "UTF-8"))
        val response = Iterator.continually(reader.readLine()).takeWhile(_ != null).mkString
        reader.close()
        conn.disconnect()

        decode[OllamaResponse](response).toOption.map(_.response)
      } else {
        val errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream, "UTF-8"))
        val errorMsg = Iterator.continually(errorReader.readLine()).takeWhile(_ != null).mkString
        errorReader.close()
        conn.disconnect()
        logWarn(s"Ollama returned $responseCode: $errorMsg")
        None
      }
    } match {
      case Success(result) => result
      case Failure(e) =>
        logWarn(s"Ollama call failed: ${e.getMessage}")
        None
    }
  }

  /**
   * Parse LLM response to Verdict
   */
  private def parseVerdict(response: String): Option[LlmVerdict] = {
    // Try to extract JSON object from response (LLM might include extra text)
    val jsonPattern = """\{[^{}]*"predicate"[^{}]*\}""".r

    jsonPattern.findFirstIn(response).flatMap { json =>
      decode[LlmVerdict](json) match {
        case Right(v) => Some(v)
        case Left(err) =>
          logWarn(s"JSON parse error: ${err.getMessage}")
          None
      }
    }
  }

  /**
   * Validate verdict has acceptable predicate
   */
  private def isValidVerdict(v: LlmVerdict): Boolean =
    LlmPromptTemplates.ValidPredicates.contains(v.predicate) &&
      v.confidence >= 0.0 && v.confidence <= 1.0

  /**
   * Fallback heuristic scoring when LLM fails
   */
  private def heuristicScore(candidate: RelationCandidate): LlmVerdict = {
    // Simple heuristic based on concept properties
    val predicate = inferPredicate(candidate.a.lemma, candidate.b.lemma)
    LlmVerdict(
      predicate = predicate,
      confidence = 0.65,
      evidence = s"Heuristic: ${candidate.evidence}",
      ref = "heuristic-fallback"
    )
  }

  /**
   * Simple predicate inference heuristic
   */
  private def inferPredicate(lemmaA: String, lemmaB: String): String = {
    val a = lemmaA.toLowerCase
    val b = lemmaB.toLowerCase

    if (a.contains(b) || b.contains(a)) "related_to"
    else if (a.endsWith("ing") || a.endsWith("tion")) "causes"
    else if (b.endsWith("ing") || b.endsWith("tion")) "enables"
    else "related_to"
  }

  /**
   * Escape string for JSON embedding
   */
  private def escapeJson(s: String): String =
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
}

/**
 * Ollama API response structure
 */
case class OllamaResponse(
                           model: String = "",
                           response: String,
                           done: Boolean = true
                         )