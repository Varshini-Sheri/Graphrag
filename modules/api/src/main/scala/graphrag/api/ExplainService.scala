package graphrag.api

import graphrag.core.config.OllamaConfig
import graphrag.core.model._
import graphrag.core.utils.Logging
import graphrag.llm.OllamaClient
import org.neo4j.driver.Driver
import org.neo4j.driver.Value
import scala.collection.JavaConverters._

/**
 * Service for generating natural language explanations of concepts and relations.
 */
class ExplainService(driver: Driver, ollamaClient: OllamaClient) extends Logging {

  private val evidenceService = EvidenceService(driver)

  /**
   * Explain a concept in natural language
   */
  def explainConcept(conceptId: String): ExplanationResult = {
    logInfo(s"Generating explanation for concept: $conceptId")

    // Get concept details
    val conceptOpt = getConcept(conceptId)

    conceptOpt match {
      case Some(concept) =>
        // Get related concepts
        val related = getRelatedConcepts(conceptId, limit = 5)
        val evidence = evidenceService.getEvidenceForConcept(conceptId)

        // Build context for LLM
        val context = buildConceptContext(concept, related, evidence)

        // Generate explanation
        val explanation = generateExplanation(context)

        ExplanationResult(
          success = true,
          subject = concept.surface,
          explanation = explanation,
          relatedConcepts = related.map(_.surface),
          confidence = 0.85
        )

      case None =>
        ExplanationResult(
          success = false,
          subject = conceptId,
          explanation = s"Concept not found: $conceptId",
          relatedConcepts = Seq.empty,
          confidence = 0.0
        )
    }
  }

  /**
   * Explain a relation between two concepts
   */
  def explainRelation(fromConceptId: String, toConceptId: String): ExplanationResult = {
    logInfo(s"Generating explanation for relation: $fromConceptId -> $toConceptId")

    val fromOpt = getConcept(fromConceptId)
    val toOpt = getConcept(toConceptId)

    (fromOpt, toOpt) match {
      case (Some(from), Some(to)) =>
        val evidence = evidenceService.getEvidenceForRelation(fromConceptId, toConceptId)
        val context = buildRelationContext(from, to, evidence)
        val explanation = generateExplanation(context)

        ExplanationResult(
          success = true,
          subject = s"${from.surface} → ${to.surface}",
          explanation = explanation,
          relatedConcepts = Seq(from.surface, to.surface),
          confidence = evidence.headOption.map(_.confidence).getOrElse(0.5)
        )

      case _ =>
        ExplanationResult(
          success = false,
          subject = s"$fromConceptId → $toConceptId",
          explanation = "One or both concepts not found",
          relatedConcepts = Seq.empty,
          confidence = 0.0
        )
    }
  }

  /**
   * Explain a path between two concepts
   */
  def explainPath(fromConceptId: String, toConceptId: String, maxHops: Int = 3): ExplanationResult = {
    logInfo(s"Explaining path from $fromConceptId to $toConceptId (max $maxHops hops)")

    val path = findPath(fromConceptId, toConceptId, maxHops)

    if (path.nonEmpty) {
      val context = buildPathContext(path)
      val explanation = generateExplanation(context)

      ExplanationResult(
        success = true,
        subject = s"Path: ${path.map(_.surface).mkString(" → ")}",
        explanation = explanation,
        relatedConcepts = path.map(_.surface),
        confidence = 0.75
      )
    } else {
      ExplanationResult(
        success = false,
        subject = s"$fromConceptId → $toConceptId",
        explanation = s"No path found within $maxHops hops",
        relatedConcepts = Seq.empty,
        confidence = 0.0
      )
    }
  }

  private def getConcept(conceptId: String): Option[Concept] = {
    import graphrag.neo4j.Neo4jUtils
    import org.neo4j.driver.Values

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
          name = node.get("name").asString()
        ))
      } else None
    }
  }

  private def getRelatedConcepts(conceptId: String, limit: Int): Seq[Concept] = {
    import graphrag.neo4j.Neo4jUtils
    import org.neo4j.driver.Values

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
          name = node.get("name").asString()
        )
      }.toSeq
    }
  }

  private def findPath(fromId: String, toId: String, maxHops: Int): Seq[Concept] = {
    import graphrag.neo4j.Neo4jUtils
    import org.neo4j.driver.Values

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
            name = n.get("name").asString()
          )
        }.toSeq
      } else Seq.empty
    }
  }


  private def buildConceptContext(concept: Concept, related: Seq[Concept], evidence: Seq[Evidence]): String = {
    s"""Concept: ${concept.surface} (${concept.lemma})
       |Related concepts: ${related.map(_.surface).mkString(", ")}
       |Evidence from ${evidence.flatMap(_.chunks).size} text chunks.
       |Sample text: ${evidence.flatMap(_.chunks).take(2).map(_.text).mkString(" ... ")}
       |
       |Provide a clear, concise explanation of this concept based on the context.""".stripMargin
  }

  private def buildRelationContext(from: Concept, to: Concept, evidence: Seq[Evidence]): String = {
    val predicate = evidence.headOption.map(_.predicate).getOrElse("related_to")
    s"""Relationship: ${from.surface} --[$predicate]--> ${to.surface}
       |Evidence: ${evidence.headOption.map(_.explanation).getOrElse("No direct evidence")}
       |Context: ${evidence.flatMap(_.chunks).take(2).map(_.text).mkString(" ... ")}
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
}

/**
 * Result of an explanation request
 */
case class ExplanationResult(
                              success: Boolean,
                              subject: String,
                              explanation: String,
                              relatedConcepts: Seq[String],
                              confidence: Double
                            )

object ExplainService {
  def apply(driver: Driver, ollamaClient: OllamaClient): ExplainService =
    new ExplainService(driver, ollamaClient)

  def apply(driver: Driver, ollamaConfig: OllamaConfig): ExplainService =
    new ExplainService(driver, OllamaClient(ollamaConfig))
}