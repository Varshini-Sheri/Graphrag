package graphrag.api

import graphrag.core.model._
import graphrag.core.utils.Logging
import graphrag.neo4j.Neo4jUtils
import org.neo4j.driver.{Driver, Values, Value}
import org.neo4j.driver.types.Node
import scala.collection.JavaConverters._
import scala.util.Try
import io.circe._
import io.circe.generic.semiauto._
import Neo4jValueHelpers._

/**
 * Service for retrieving evidence supporting relations in the knowledge graph.
 * Returns stored snippets with proper paper references per specification.
 * FIXED: All Neo4j type issues resolved using Neo4jValueHelpers
 */
class EvidenceService(driver: Driver) extends Logging {

  def getEvidenceById(evidenceId: String): Option[EvidenceResponse] = {
    if (!evidenceId.startsWith("evid:")) {
      return None
    }

    val chunkId = evidenceId.stripPrefix("evid:")

    Neo4jUtils.withSession(driver) { session =>
      val query = """
                    |MATCH (chunk:Chunk {id: $chunkId})
                    |MATCH (p:Paper)-[:HAS_CHUNK]->(chunk)
                    |OPTIONAL MATCH (chunk)-[:MENTIONS]->(c:Concept)
                    |RETURN chunk, p, collect(DISTINCT c) as concepts
      """.stripMargin

      val result = session.run(
        query,
        Values.parameters("chunkId", chunkId)
      )

      if (result.hasNext) {
        val record = result.next()
        val chunk = record.get("chunk").asNode()
        val paper = record.get("p").asNode()

        // FIXED: Use Neo4jValueHelpers for concepts list
        val concepts = record.get("concepts").asList().asScala.flatMap { c =>
          extractNode(c).map(node => getNodeString(node, "surface"))
        }.toSeq

        Some(EvidenceResponse(
          evidenceId = evidenceId,
          paperId = getNodeString(paper, "id"),
          chunkId = chunkId,
          text = getNodeString(chunk, "text"),
          docRef = DocRef(
            title = getNodeString(paper, "title"),
            year = getNodeInt(paper, "year"),
            url = getNodeString(paper, "url"),
            authors = if (paper.containsKey("authors"))
              Some(getNodeString(paper, "authors")) else None
          ),
          span = if (chunk.containsKey("spanStart") && chunk.containsKey("spanEnd"))
            Some(s"p.${getNodeInt(chunk, "spanStart")}-${getNodeInt(chunk, "spanEnd")}")
          else None,
          concepts = concepts
        ))
      } else {
        None
      }
    }
  }

  def getEvidenceForRelation(fromConceptId: String, toConceptId: String): Seq[EvidenceResponse] = {
    Neo4jUtils.withSession(driver) { session =>
      val query = """
                    |MATCH (a:Concept {id: $fromId})-[r:RELATES_TO]->(b:Concept {id: $toId})
                    |OPTIONAL MATCH (p:Paper)-[:HAS_CHUNK]->(chunk:Chunk)-[:MENTIONS]->(a)
                    |WHERE (chunk)-[:MENTIONS]->(b)
                    |RETURN r, p, chunk, a.surface as fromSurface, b.surface as toSurface
                    |LIMIT 10
      """.stripMargin

      val params = Values.parameters(
        "fromId", fromConceptId,
        "toId", toConceptId
      )
      val result = session.run(query, params)

      result.list().asScala.flatMap { record =>
        // FIXED: Check if chunk is null using isNullValue
        if (!isNullValue(record.get("chunk"))) {
          val chunk = record.get("chunk").asNode()
          val paper = record.get("p").asNode()
          val relation = record.get("r").asRelationship()

          val evidenceId = s"evid:${getNodeString(chunk, "id")}"

          Some(EvidenceResponse(
            evidenceId = evidenceId,
            paperId = getNodeString(paper, "id"),
            chunkId = getNodeString(chunk, "id"),
            text = getNodeString(chunk, "text"),
            docRef = DocRef(
              title = getNodeString(paper, "title"),
              year = getNodeInt(paper, "year"),
              url = getNodeString(paper, "url"),
              authors = if (paper.containsKey("authors"))
                Some(getNodeString(paper, "authors")) else None
            ),
            span = if (chunk.containsKey("spanStart") && chunk.containsKey("spanEnd"))
              Some(s"p.${getNodeInt(chunk, "spanStart")}-${getNodeInt(chunk, "spanEnd")}")
            else None,
            concepts = Seq(
              record.get("fromSurface").asString(),
              record.get("toSurface").asString()
            ),
            relationInfo = Some(RelationInfo(
              predicate = if (relation.containsKey("predicate"))
                relation.get("predicate").asString("") else "",
              confidence = if (relation.containsKey("confidence"))
                relation.get("confidence").asDouble(0.0) else 0.0
            ))
          ))
        } else {
          None
        }
      }.toSeq
    }
  }

  def getEvidenceForConcept(conceptId: String, limit: Int = 10): Seq[EvidenceResponse] = {
    Neo4jUtils.withSession(driver) { session =>
      val query = """
                    |MATCH (c:Concept {id: $conceptId})<-[:MENTIONS]-(chunk:Chunk)<-[:HAS_CHUNK]-(p:Paper)
                    |OPTIONAL MATCH (chunk)-[:MENTIONS]->(related:Concept)
                    |WHERE related.id <> $conceptId
                    |WITH chunk, p, c, collect(DISTINCT related.surface) as relatedSurfaces
                    |RETURN chunk, p, c.surface as conceptSurface, relatedSurfaces
                    |LIMIT $limit
      """.stripMargin

      val params = Values.parameters("conceptId", conceptId, "limit", Long.box(limit))
      val result = session.run(query, params)

      result.list().asScala.map { record =>
        val chunk = record.get("chunk").asNode()
        val paper = record.get("p").asNode()
        val conceptSurface = record.get("conceptSurface").asString()

        // FIXED: Use Neo4jValueHelpers for relatedSurfaces list
        val relatedSurfaces = record.get("relatedSurfaces").asList().asScala.flatMap { v =>
          if (!isNullValue(v)) {
            toValue(v).map(_.asString())
          } else None
        }.toSeq

        val evidenceId = s"evid:${getNodeString(chunk, "id")}"

        EvidenceResponse(
          evidenceId = evidenceId,
          paperId = getNodeString(paper, "id"),
          chunkId = getNodeString(chunk, "id"),
          text = getNodeString(chunk, "text"),
          docRef = DocRef(
            title = getNodeString(paper, "title"),
            year = getNodeInt(paper, "year"),
            url = getNodeString(paper, "url"),
            authors = if (paper.containsKey("authors"))
              Some(getNodeString(paper, "authors")) else None
          ),
          span = if (chunk.containsKey("spanStart") && chunk.containsKey("spanEnd"))
            Some(s"p.${getNodeInt(chunk, "spanStart")}-${getNodeInt(chunk, "spanEnd")}")
          else None,
          concepts = Seq(conceptSurface) ++ relatedSurfaces
        )
      }.toSeq
    }
  }

  def getCoOccurrenceEvidence(conceptA: String, conceptB: String, limit: Int = 10): Seq[EvidenceResponse] = {
    Neo4jUtils.withSession(driver) { session =>
      val query = """
                    |MATCH (a:Concept {id: $aId}), (b:Concept {id: $bId})
                    |MATCH (chunk:Chunk)-[:MENTIONS]->(a)
                    |WHERE (chunk)-[:MENTIONS]->(b)
                    |MATCH (p:Paper)-[:HAS_CHUNK]->(chunk)
                    |OPTIONAL MATCH (a)-[r:CO_OCCURS]-(b)
                    |RETURN chunk, p, a.surface as aSurface, b.surface as bSurface, r
                    |LIMIT $limit
      """.stripMargin

      val params = Values.parameters(
        "aId", conceptA,
        "bId", conceptB,
        "limit", Long.box(limit)
      )
      val result = session.run(query, params)

      result.list().asScala.map { record =>
        val chunk = record.get("chunk").asNode()
        val paper = record.get("p").asNode()
        val aSurface = record.get("aSurface").asString()
        val bSurface = record.get("bSurface").asString()

        val evidenceId = s"evid:${getNodeString(chunk, "id")}"

        // FIXED: Use isNullValue for relationship check
        val relationInfo = if (!isNullValue(record.get("r"))) {
          val rel = record.get("r").asRelationship()
          Some(RelationInfo(
            predicate = "co_occurs",
            confidence = if (rel.containsKey("confidence"))
              rel.get("confidence").asDouble(0.0) else 0.0,
            frequency = if (rel.containsKey("freq"))
              Some(rel.get("freq").asLong()) else None
          ))
        } else None

        EvidenceResponse(
          evidenceId = evidenceId,
          paperId = getNodeString(paper, "id"),
          chunkId = getNodeString(chunk, "id"),
          text = getNodeString(chunk, "text"),
          docRef = DocRef(
            title = getNodeString(paper, "title"),
            year = getNodeInt(paper, "year"),
            url = getNodeString(paper, "url"),
            authors = if (paper.containsKey("authors"))
              Some(getNodeString(paper, "authors")) else None
          ),
          span = if (chunk.containsKey("spanStart") && chunk.containsKey("spanEnd"))
            Some(s"p.${getNodeInt(chunk, "spanStart")}-${getNodeInt(chunk, "spanEnd")}")
          else None,
          concepts = Seq(aSurface, bSurface),
          relationInfo = relationInfo
        )
      }.toSeq
    }
  }

  def getBatchEvidence(evidenceIds: Seq[String]): Seq[EvidenceResponse] = {
    evidenceIds.flatMap(getEvidenceById)
  }
}

case class EvidenceResponse(
                             evidenceId: String,
                             paperId: String,
                             chunkId: String,
                             text: String,
                             docRef: DocRef,
                             span: Option[String] = None,
                             concepts: Seq[String] = Seq.empty,
                             relationInfo: Option[RelationInfo] = None
                           )

case class DocRef(
                   title: String,
                   year: Int,
                   url: String,
                   authors: Option[String] = None
                 )

case class RelationInfo(
                         predicate: String,
                         confidence: Double,
                         frequency: Option[Long] = None
                       )

object EvidenceServiceEncoders {
  implicit val docRefEncoder: Encoder[DocRef] = deriveEncoder
  implicit val relationInfoEncoder: Encoder[RelationInfo] = deriveEncoder
  implicit val evidenceResponseEncoder: Encoder[EvidenceResponse] = deriveEncoder

  implicit val docRefDecoder: Decoder[DocRef] = deriveDecoder
  implicit val relationInfoDecoder: Decoder[RelationInfo] = deriveDecoder
  implicit val evidenceResponseDecoder: Decoder[EvidenceResponse] = deriveDecoder
}

object EvidenceService {
  def apply(driver: Driver): EvidenceService = new EvidenceService(driver)
}