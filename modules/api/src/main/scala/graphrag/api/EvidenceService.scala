package graphrag.api

import graphrag.core.config.Neo4jConfig
import graphrag.core.model._
import graphrag.core.utils.Logging
import graphrag.neo4j.Neo4jUtils
import org.neo4j.driver.{Driver, Values, Value}

import scala.collection.JavaConverters._
import scala.util.Try

/**
 * Service for retrieving evidence supporting relations in the knowledge graph.
 */
class EvidenceService(driver: Driver) extends Logging {

  /**
   * Get evidence for a specific relation
   */
  def getEvidenceForRelation(fromConceptId: String, toConceptId: String): Seq[Evidence] = {
    Neo4jUtils.withSession(driver) { session =>
      val query =
        """
          |MATCH (a:Concept {id: $fromId})-[r:RELATES_TO]->(b:Concept {id: $toId})
          |OPTIONAL MATCH (a)<-[:MENTIONS]-(chunk:Chunk)-[:MENTIONS]->(b)
          |RETURN a, r, b, collect(chunk) AS chunks
      """.stripMargin

      val params = Values.parameters(
        "fromId", fromConceptId,
        "toId", toConceptId
      )
      val result = session.run(query, params)

      if (!result.hasNext) {
        Seq.empty
      } else {
        val record = result.next()

        val relation = record.get("r").asRelationship()
        val chunksValue = record.get("chunks")

        val chunks =
          if (chunksValue == null || chunksValue.isNull) {
            Seq.empty[ChunkEvidence]
          } else {
            chunksValue
              .asList((v: Value) => v)
              .asScala
              .collect {
                case v if !v.isNull =>
                  val node = v.asNode()
                  ChunkEvidence(
                    chunkId = node.get("id").asString(),
                    text    = node.get("text").asString(),
                    docId   = node.get("docId").asString()
                  )
              }
              .toSeq
          }

        Seq(
          Evidence(
            predicate = relation.get("predicate").asString(),
            confidence = relation.get("confidence").asDouble(),
            explanation = relation.get("evidence").asString(), // or "explanation"
            chunks = chunks
          )
        )
      }
    }
  }


  /**
   * Get all evidence for a concept
   */
  def getEvidenceForConcept(conceptId: String): Seq[Evidence] = {
    Neo4jUtils.withSession(driver) { session =>
      val query = """
                    |MATCH (c:Concept {id: $conceptId})<-[:MENTIONS]-(chunk:Chunk)
                    |RETURN c, collect(chunk) AS chunks
      """.stripMargin

      val params = Values.parameters("conceptId", conceptId)
      val result = session.run(query, params)

      if (result.hasNext) {
        val record = result.next()
        val chunksValue = record.get("chunks")

        val chunks = if (!chunksValue.isNull) {
          chunksValue.asList((v: Value) => v).asScala.map { chunkValue =>

            val node = chunkValue.asNode()
            ChunkEvidence(
              chunkId = node.get("id").asString(),
              text = node.get("text").asString(),
              docId = node.get("docId").asString()
            )
          }.toSeq
        } else {
          Seq.empty[ChunkEvidence]
        }

        Seq(Evidence(
          predicate = "mentioned_in",
          confidence = 1.0,
          explanation = s"Concept appears in ${chunks.size} chunks",
          chunks = chunks
        ))
      } else {
        Seq.empty
      }
    }
  }

  /**
   * Get co-occurrence evidence between two concepts
   */
  def getCoOccurrenceEvidence(conceptA: String, conceptB: String): Seq[Evidence] = {
    Neo4jUtils.withSession(driver) { session =>
      val query = """
                    |MATCH (a:Concept {id: $aId})-[r:CO_OCCURS]-(b:Concept {id: $bId})
                    |MATCH (chunk:Chunk)-[:MENTIONS]->(a)
                    |WHERE (chunk)-[:MENTIONS]->(b)
                    |RETURN r, collect(DISTINCT chunk) AS chunks
      """.stripMargin

      val params = Values.parameters(
        "aId", conceptA,
        "bId", conceptB
      )
      val result = session.run(query, params)

      result.list().asScala.map { record =>
        val relation = record.get("r").asRelationship()
        val chunksValue = record.get("chunks")

        val chunks = if (!chunksValue.isNull) {
          chunksValue.asList((v: Value) => v).asScala.map { chunkValue =>
            val node = chunkValue.asNode()
            ChunkEvidence(
              chunkId = node.get("id").asString(),
              text = node.get("text").asString(),
              docId = node.get("docId").asString()
            )
          }.toSeq
        } else {
          Seq.empty[ChunkEvidence]
        }

        Evidence(
          predicate = "co_occurs",
          confidence = 0.8,
          explanation = s"Co-occurred ${relation.get("freq").asLong()} times",
          chunks = chunks
        )
      }.toSeq
    }
  }
}

/**
 * Evidence supporting a relation
 */
case class Evidence(
                     predicate: String,
                     confidence: Double,
                     explanation: String,
                     chunks: Seq[ChunkEvidence]
                   )

/**
 * Chunk that provides evidence
 */
case class ChunkEvidence(
                          chunkId: String,
                          text: String,
                          docId: String
                        )

object EvidenceService {
  def apply(driver: Driver): EvidenceService = new EvidenceService(driver)

  def apply(config: Neo4jConfig): EvidenceService = {
    val driver = Neo4jUtils.createDriver(config)
    new EvidenceService(driver)
  }
}