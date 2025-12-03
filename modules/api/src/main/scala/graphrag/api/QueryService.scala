package graphrag.api

import graphrag.core.config.Neo4jConfig
import graphrag.core.model._
import graphrag.core.utils.Logging
import graphrag.neo4j.Neo4jUtils
import graphrag.llm.OllamaClient
import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import scala.collection.JavaConverters._

/**
 * Service for querying the knowledge graph using natural language.
 * Implements RAG (Retrieval Augmented Generation) pattern.
 */
class QueryService(driver: Driver, ollamaClient: OllamaClient) extends Logging {

  /**
   * Query the knowledge graph with a natural language question
   */
  def query(question: String, maxResults: Int = 10): QueryResult = {
    logInfo(s"Processing query: $question")

    // Step 1: Extract concepts from question
    val queryConcepts = extractQueryConcepts(question)
    logInfo(s"Extracted ${queryConcepts.size} concepts from query")

    // Step 2: Find matching concepts in graph
    val matchedConcepts = findMatchingConcepts(queryConcepts, maxResults)
    logInfo(s"Found ${matchedConcepts.size} matching concepts")

    // Step 3: Retrieve relevant context
    val context = retrieveContext(matchedConcepts, maxResults)
    logInfo(s"Retrieved ${context.size} context items")

    // Step 4: Generate answer using LLM
    val answer = generateAnswer(question, context)

    QueryResult(
      question = question,
      answer = answer,
      concepts = matchedConcepts,
      context = context,
      confidence = calculateConfidence(matchedConcepts, context)
    )
  }

  /**
   * Find concepts by keyword search
   */
  def searchConcepts(keyword: String, limit: Int = 20): Seq[Concept] = {
    Neo4jUtils.withSession(driver) { session =>
      val query = """
                    |MATCH (c:Concept)
                    |WHERE toLower(c.lemma) CONTAINS toLower($keyword)
                    |   OR toLower(c.surface) CONTAINS toLower($keyword)
                    |RETURN c
                    |LIMIT $limit
      """.stripMargin

      val result = session.run(
        query,
        Values.parameters("keyword", keyword, "limit", Long.box(limit))
      )


      result.list().asScala.map { record =>
        val node = record.get("c").asNode()
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

  /**
   * Get related concepts
   */
  def getRelatedConcepts(conceptId: String, limit: Int = 10): Seq[RelatedConcept] = {
    Neo4jUtils.withSession(driver) { session =>
      val query = """
                    |MATCH (c:Concept {id: $conceptId})-[r:RELATES_TO]-(related:Concept)
                    |RETURN related, r.predicate AS predicate, r.confidence AS confidence
                    |ORDER BY confidence DESC
                    |LIMIT $limit
      """.stripMargin

      val result = session.run(
        query,
        Values.parameters("conceptId", conceptId, "limit", Long.box(limit))
      )


      result.list().asScala.map { record =>
        val node = record.get("related").asNode()
        RelatedConcept(
          concept = Concept(
            conceptId = node.get("id").asString(),
            lemma = node.get("lemma").asString(),
            surface = node.get("surface").asString(),
            origin = node.get("origin").asString(),
            name = node.get("name").asString()
          ),
          predicate = record.get("predicate").asString(),
          confidence = record.get("confidence").asDouble()
        )
      }.toSeq
    }
  }

  /**
   * Get chunks mentioning a concept
   */
  def getChunksForConcept(conceptId: String, limit: Int = 10): Seq[Chunk] = {
    Neo4jUtils.withSession(driver) { session =>
      val query = """
                    |MATCH (chunk:Chunk)-[:MENTIONS]->(c:Concept {id: $conceptId})
                    |RETURN chunk
                    |LIMIT $limit
      """.stripMargin

      val result = session.run(
        query,
        Values.parameters("conceptId", conceptId, "limit", Long.box(limit))
      )


      result.list().asScala.map { record =>
        val node = record.get("chunk").asNode()
        Chunk(
          chunkId = node.get("id").asString(),
          docId = node.get("docId").asString(),
          span = (node.get("spanStart").asInt(), node.get("spanEnd").asInt()),
          text = node.get("text").asString(),
          sourceUri = node.get("sourceUri").asString(),
          hash = node.get("hash").asString()
        )
      }.toSeq
    }
  }

  private def extractQueryConcepts(question: String): Seq[String] = {
    // Simple keyword extraction
    val stopWords = Set("what", "is", "are", "how", "does", "do", "the", "a", "an", "in", "on", "for", "to", "of", "and", "or")
    question
      .toLowerCase
      .replaceAll("[^a-z0-9\\s]", "")
      .split("\\s+")
      .filter(word => word.length > 2 && !stopWords.contains(word))
      .toSeq
  }

  private def findMatchingConcepts(queryTerms: Seq[String], limit: Int): Seq[Concept] = {
    queryTerms.flatMap { term =>
      searchConcepts(term, limit / queryTerms.size.max(1))
    }.distinct.take(limit)
  }

  private def retrieveContext(concepts: Seq[Concept], maxItems: Int): Seq[ContextItem] = {
    concepts.flatMap { concept =>
      // Get chunks
      val chunks = getChunksForConcept(concept.conceptId, 3)

      // Get related concepts
      val related = getRelatedConcepts(concept.conceptId, 3)

      chunks.map { chunk =>
        ContextItem(
          conceptId = concept.conceptId,
          conceptSurface = concept.surface,
          text = chunk.text,
          relatedConcepts = related.map(_.concept.surface),
          sourceUri = chunk.sourceUri
        )
      }
    }.take(maxItems)
  }

  private def generateAnswer(question: String, context: Seq[ContextItem]): String = {
    if (context.isEmpty) {
      return "I couldn't find relevant information in the knowledge graph to answer your question."
    }

    val contextText = context.map { item =>
      s"[${item.conceptSurface}]: ${item.text}"
    }.mkString("\n\n")

    val prompt = s"""Based on the following context from a knowledge graph about software engineering research, answer the question.

Context:
$contextText

Question: $question

Provide a clear, concise answer based only on the information in the context. If the context doesn't contain enough information, say so."""

    ollamaClient.complete(prompt).getOrElse(
      "Unable to generate an answer. Please try again."
    )
  }

  private def calculateConfidence(concepts: Seq[Concept], context: Seq[ContextItem]): Double = {
    if (concepts.isEmpty) 0.0
    else if (context.isEmpty) 0.2
    else {
      val coverage = context.size.toDouble / concepts.size.min(10)
      Math.min(0.5 + (coverage * 0.5), 0.95)
    }
  }
}

// Response models
case class QueryResult(
                        question: String,
                        answer: String,
                        concepts: Seq[Concept],
                        context: Seq[ContextItem],
                        confidence: Double
                      )

case class ContextItem(
                        conceptId: String,
                        conceptSurface: String,
                        text: String,
                        relatedConcepts: Seq[String],
                        sourceUri: String
                      )

case class RelatedConcept(
                           concept: Concept,
                           predicate: String,
                           confidence: Double
                         )

object QueryService {
  def apply(driver: Driver, ollamaClient: OllamaClient): QueryService =
    new QueryService(driver, ollamaClient)

  def apply(config: Neo4jConfig, ollamaClient: OllamaClient): QueryService = {
    val driver = Neo4jUtils.createDriver(config)
    new QueryService(driver, ollamaClient)
  }
}