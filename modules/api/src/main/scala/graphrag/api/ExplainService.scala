package graphrag.api

import graphrag.core.model._
import graphrag.core.utils.Logging
import graphrag.llm.OllamaClient
import graphrag.neo4j.Neo4jUtils
import org.neo4j.driver.{Driver, Values, Value}
import scala.collection.JavaConverters._
import scala.util.Try
import io.circe._
import io.circe.generic.semiauto._

/**
 * Service for generating natural language explanations and execution traces.
 * Per specification: returns traces showing how answers were derived.
 */
class ExplainService(driver: Driver, ollamaClient: OllamaClient) extends Logging {

  /**
   * Get execution trace for a past query
   */
  def getExecutionTrace(requestId: String): Option[ExecutionTrace] = {
    // In production, this would retrieve from a trace store
    // For now, reconstruct from query parameters stored in job metadata
    logInfo(s"Retrieving execution trace for request: $requestId")

    // This would typically query a trace database
    // Returning example structure for now
    Some(ExecutionTrace(
      requestId = requestId,
      plan = Seq(
        PlanStep("matchTask", Some("MATCH (t:Task {name: $taskName})"), None),
        PlanStep("filterTime", None, Some("p.year >= $fromYear AND p.year <= $toYear")),
        PlanStep("baselineEdge", None, Some("IMPROVES_OVER metric=$metric baseline=$baseline"))
      ),
      promptVersions = Map("relationScoring" -> "v3.2"),
      counters = ExecutionCounters(
        nodesRead = 0,
        relsRead = 0,
        llmCalls = 0,
        cacheHits = 0
      )
    ))
  }

  /**
   * Explain a concept in natural language
   */
  def explainConcept(conceptId: String, includeTrace: Boolean = false): ExplanationResult = {
    logInfo(s"Generating explanation for concept: $conceptId")

    val trace = if (includeTrace) scala.collection.mutable.ArrayBuffer[PlanStep]() else null

    if (trace != null) {
      trace += PlanStep("getConcept", Some(s"MATCH (c:Concept {id: '$conceptId'}) RETURN c"), None)
    }

    // Get concept details
    val conceptOpt = getConcept(conceptId)

    conceptOpt match {
      case Some(concept) =>
        if (trace != null) {
          trace += PlanStep("getRelated", Some(s"MATCH (c)-[:RELATES_TO]-(related) RETURN related LIMIT 5"), None)
        }

        // Get related concepts
        val related = getRelatedConcepts(conceptId, limit = 5)

        // Get evidence from chunks
        val evidence = getConceptEvidence(conceptId)

        // Build context for LLM
        val context = buildConceptContext(concept, related, evidence)

        // Generate explanation
        val explanation = generateExplanation(context)

        if (trace != null) {
          trace += PlanStep("llmGeneration", None, Some(s"Generated explanation with ${explanation.length} chars"))
        }

        ExplanationResult(
          success = true,
          subject = concept.surface,
          explanation = explanation,
          relatedConcepts = related.map(_.surface),
          confidence = calculateConceptConfidence(evidence),
          trace = if (includeTrace) Some(trace.toSeq) else None
        )

      case None =>
        ExplanationResult(
          success = false,
          subject = conceptId,
          explanation = s"Concept not found: $conceptId",
          relatedConcepts = Seq.empty,
          confidence = 0.0,
          trace = if (includeTrace) Some(trace.toSeq) else None
        )
    }
  }

  /**
   * Explain a relation between two concepts
   */
  def explainRelation(
                       fromConceptId: String,
                       toConceptId: String,
                       includeTrace: Boolean = false
                     ): ExplanationResult = {
    logInfo(s"Generating explanation for relation: $fromConceptId -> $toConceptId")

    val trace = if (includeTrace) scala.collection.mutable.ArrayBuffer[PlanStep]() else null

    if (trace != null) {
      trace += PlanStep("getConcepts",
        Some(s"MATCH (from:Concept {id: '$fromConceptId'}), (to:Concept {id: '$toConceptId'}) RETURN from, to"),
        None)
    }

    val fromOpt = getConcept(fromConceptId)
    val toOpt = getConcept(toConceptId)

    (fromOpt, toOpt) match {
      case (Some(from), Some(to)) =>
        if (trace != null) {
          trace += PlanStep("getRelation",
            Some(s"MATCH (from)-[r:RELATES_TO]->(to) RETURN r"),
            None)
        }

        val evidence = getRelationEvidence(fromConceptId, toConceptId)
        val context = buildRelationContext(from, to, evidence)
        val explanation = generateExplanation(context)

        val confidence = if (evidence.nonEmpty) {
          evidence.map(_._2).sum / evidence.size
        } else 0.5

        if (trace != null) {
          trace += PlanStep("llmGeneration", None, Some(s"Generated explanation using ${evidence.size} evidence items"))
        }

        ExplanationResult(
          success = true,
          subject = s"${from.surface} → ${to.surface}",
          explanation = explanation,
          relatedConcepts = Seq(from.surface, to.surface),
          confidence = confidence,
          trace = if (includeTrace) Some(trace.toSeq) else None
        )

      case _ =>
        ExplanationResult(
          success = false,
          subject = s"$fromConceptId → $toConceptId",
          explanation = "One or both concepts not found",
          relatedConcepts = Seq.empty,
          confidence = 0.0,
          trace = if (includeTrace) Some(trace.toSeq) else None
        )
    }
  }

  /**
   * Explain a path between two concepts
   */
  def explainPath(
                   fromConceptId: String,
                   toConceptId: String,
                   maxHops: Int = 3,
                   includeTrace: Boolean = false
                 ): ExplanationResult = {
    logInfo(s"Explaining path from $fromConceptId to $toConceptId (max $maxHops hops)")

    val trace = if (includeTrace) scala.collection.mutable.ArrayBuffer[PlanStep]() else null

    if (trace != null) {
      trace += PlanStep("findPath",
        Some(s"MATCH path = shortestPath((a)-[:RELATES_TO*1..$maxHops]-(b)) RETURN path"),
        None)
    }

    val path = findPath(fromConceptId, toConceptId, maxHops)

    if (path.nonEmpty) {
      val context = buildPathContext(path)
      val explanation = generateExplanation(context)

      if (trace != null) {
        trace += PlanStep("pathFound", None, Some(s"Found path with ${path.size} nodes"))
        trace += PlanStep("llmGeneration", None, Some("Generated path explanation"))
      }

      ExplanationResult(
        success = true,
        subject = s"Path: ${path.map(_.surface).mkString(" → ")}",
        explanation = explanation,
        relatedConcepts = path.map(_.surface),
        confidence = calculatePathConfidence(path.size, maxHops),
        trace = if (includeTrace) Some(trace.toSeq) else None
      )
    } else {
      ExplanationResult(
        success = false,
        subject = s"$fromConceptId → $toConceptId",
        explanation = s"No path found within $maxHops hops",
        relatedConcepts = Seq.empty,
        confidence = 0.0,
        trace = if (includeTrace) Some(trace.toSeq) else None
      )
    }
  }

  private def getConcept(conceptId: String): Option[Concept] = {
    Neo4jUtils.withSession(driver) { session =>
      val result = session.run(
        "MATCH (c:Concept {id: $id}) RETURN c",
        Values.parameters("id", conceptId)
      )

      if (result.hasNext) {
        val node = result.next().get("c").asNode()
        Some(Concept(
          conceptId = node.get("id").asString(),
          lemma = node.get("lemma").asString(),
          surface = node.get("surface").asString(),
          origin = node.get("origin").asString(),
          name = node.get("name").asString("")  // Your Concept has name field
        ))
      } else None
    }
  }

  private def getRelatedConcepts(conceptId: String, limit: Int): Seq[Concept] = {
    Neo4jUtils.withSession(driver) { session =>
      val result = session.run(
        """MATCH (c:Concept {id: $id})-[:RELATES_TO|CO_OCCURS]-(related:Concept)
          |RETURN DISTINCT related LIMIT $limit""".stripMargin,
        Values.parameters("id", conceptId, "limit", Long.box(limit))
      )

      result.list().asScala.map { record =>
        val node = record.get("related").asNode()
        Concept(
          conceptId = node.get("id").asString(),
          lemma = node.get("lemma").asString(),
          surface = node.get("surface").asString(),
          origin = node.get("origin").asString(),
          name = node.get("name").asString("")  // Your Concept has name field
        )
      }.toSeq
    }
  }

  private def getConceptEvidence(conceptId: String): Seq[(String, Double)] = {
    Neo4jUtils.withSession(driver) { session =>
      val result = session.run(
        """MATCH (c:Concept {id: $id})<-[:MENTIONS]-(chunk:Chunk)
          |RETURN chunk.text as text
          |LIMIT 5""".stripMargin,
        Values.parameters("id", conceptId)
      )

      result.list().asScala.map { record =>
        val text = record.get("text").asString()
        val confidence = 0.7 // Would calculate based on text quality
        (text, confidence)
      }.toSeq
    }
  }

  private def getRelationEvidence(fromId: String, toId: String): Seq[(String, Double)] = {
    Neo4jUtils.withSession(driver) { session =>
      val result = session.run(
        """MATCH (a:Concept {id: $fromId})-[r:RELATES_TO]->(b:Concept {id: $toId})
          |OPTIONAL MATCH (chunk:Chunk)-[:MENTIONS]->(a)
          |WHERE (chunk)-[:MENTIONS]->(b)
          |RETURN chunk.text as text, r.confidence as relConfidence
          |LIMIT 5""".stripMargin,
        Values.parameters("fromId", fromId, "toId", toId)
      )

      result.list().asScala.map { record =>
        val text = record.get("text").asString("")
        val confidence = if (!record.get("relConfidence").isNull)
          record.get("relConfidence").asDouble()
        else 0.5
        (text, confidence)
      }.toSeq
    }
  }

  private def findPath(fromId: String, toId: String, maxHops: Int): Seq[Concept] = {
    Neo4jUtils.withSession(driver) { session =>
      val query =
        s"""MATCH path = shortestPath((a:Concept {id: $$fromId})-[:RELATES_TO*1..$maxHops]-(b:Concept {id: $$toId}))
           |RETURN nodes(path) AS nodes""".stripMargin

      val result = session.run(
        query,
        Values.parameters("fromId", fromId, "toId", toId)
      )

      if (result.hasNext) {
        result.next().get("nodes").asList().asScala.map { v =>
          val n = v.asInstanceOf[Value].asNode()
          Concept(
            conceptId = n.get("id").asString(),
            lemma = n.get("lemma").asString(),
            surface = n.get("surface").asString(),
            origin = n.get("origin").asString(),
            name = n.get("name").asString("")  // Your Concept has name field
          )
        }.toSeq
      } else Seq.empty
    }
  }

  private def buildConceptContext(
                                   concept: Concept,
                                   related: Seq[Concept],
                                   evidence: Seq[(String, Double)]
                                 ): String = {
    s"""Concept: ${concept.surface} (${concept.lemma})
       |Related concepts: ${related.map(_.surface).mkString(", ")}
       |Evidence from ${evidence.size} text chunks.
       |Sample text: ${evidence.take(2).map(_._1).mkString(" ... ")}
       |
       |Provide a clear, concise explanation of this concept based on the context.""".stripMargin
  }

  private def buildRelationContext(
                                    from: Concept,
                                    to: Concept,
                                    evidence: Seq[(String, Double)]
                                  ): String = {
    s"""Relationship: ${from.surface} → ${to.surface}
       |Evidence: ${evidence.size} supporting chunks
       |Context: ${evidence.take(2).map(_._1).mkString(" ... ")}
       |
       |Explain this relationship in clear, natural language.""".stripMargin
  }

  private def buildPathContext(path: Seq[Concept]): String = {
    s"""Path through knowledge graph:
       |${path.map(_.surface).mkString(" → ")}
       |
       |Explain how these concepts are connected.""".stripMargin
  }

  private def generateExplanation(context: String): String = {
    ollamaClient.complete(context).getOrElse(
      "Unable to generate explanation. Please try again."
    )
  }

  private def calculateConceptConfidence(evidence: Seq[(String, Double)]): Double = {
    if (evidence.isEmpty) 0.3
    else evidence.map(_._2).sum / evidence.size
  }

  private def calculatePathConfidence(pathLength: Int, maxHops: Int): Double = {
    // Shorter paths have higher confidence
    1.0 - (pathLength.toDouble / maxHops.toDouble) * 0.5
  }
}

/**
 * Result of an explanation request
 */
case class ExplanationResult(
                              success: Boolean,
                              subject: String,
                              explanation: String,
                              relatedConcepts: Seq[String],
                              confidence: Double,
                              trace: Option[Seq[PlanStep]] = None
                            )

/**
 * Execution trace for query planning
 */
case class ExecutionTrace(
                           requestId: String,
                           plan: Seq[PlanStep],
                           promptVersions: Map[String, String],
                           counters: ExecutionCounters
                         )

case class PlanStep(
                     step: String,
                     cypher: Option[String] = None,
                     detail: Option[String] = None
                   )

case class ExecutionCounters(
                              nodesRead: Long,
                              relsRead: Long,
                              llmCalls: Int,
                              cacheHits: Int
                            )

// Circe encoders
object ExplainServiceEncoders {
  implicit val planStepEncoder: Encoder[PlanStep] = deriveEncoder
  implicit val executionCountersEncoder: Encoder[ExecutionCounters] = deriveEncoder
  implicit val executionTraceEncoder: Encoder[ExecutionTrace] = deriveEncoder
  implicit val explanationResultEncoder: Encoder[ExplanationResult] = deriveEncoder

  implicit val planStepDecoder: Decoder[PlanStep] = deriveDecoder
  implicit val executionCountersDecoder: Decoder[ExecutionCounters] = deriveDecoder
  implicit val executionTraceDecoder: Decoder[ExecutionTrace] = deriveDecoder
  implicit val explanationResultDecoder: Decoder[ExplanationResult] = deriveDecoder
}

object ExplainService {
  def apply(driver: Driver, ollamaClient: OllamaClient): ExplainService =
    new ExplainService(driver, ollamaClient)
}