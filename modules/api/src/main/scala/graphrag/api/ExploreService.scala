package graphrag.api

import graphrag.core.config.Neo4jConfig
import graphrag.core.model._
import graphrag.core.utils.Logging
import graphrag.neo4j.Neo4jUtils
import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import org.neo4j.driver.Value
import scala.collection.JavaConverters._
import io.circe._
import io.circe.generic.semiauto._
/**
 * Service for exploring the knowledge graph structure.
 */
class ExploreService(driver: Driver) extends Logging {

  /**
   * Get neighborhood of a concept
   */
  def getNeighborhood(conceptId: String, maxDepth: Int = 1): GraphNeighborhood = {
    logInfo(s"Exploring neighborhood of $conceptId (depth: $maxDepth)")

    Neo4jUtils.withSession(driver) { session =>
      val query = s"""
                     |MATCH (center:Concept {id: $$conceptId})
                     |OPTIONAL MATCH (center)-[r:RELATES_TO|CO_OCCURS*1..$maxDepth]-(neighbor:Concept)
                     |WITH center, collect(DISTINCT neighbor) AS neighbors
                     |OPTIONAL MATCH (center)-[rel:RELATES_TO|CO_OCCURS]-(n:Concept)
                     |RETURN center, neighbors, collect(DISTINCT {from: startNode(rel).id, to: endNode(rel).id, type: type(rel), props: properties(rel)}) AS edges
      """.stripMargin

      val result = session.run(
        query,
        Values.parameters("conceptId", conceptId)
      )


      if (result.hasNext) {
        val record = result.next()
        val centerNode = record.get("center").asNode()
        val center = nodeToConcept(centerNode)

        val neighbors = record.get("neighbors").asList().asScala.map { v =>
          val node = v.asInstanceOf[Value].asNode()
          nodeToConcept(node)
        }.toSeq


        val edges = record.get("edges").asList().asScala.flatMap { v =>
          val map = v.asInstanceOf[Value].asMap().asScala

          for {
            from <- map.get("from").map(_.toString)
            to <- map.get("to").map(_.toString)
            relType <- map.get("type").map(_.toString)
          } yield GraphEdge(from, to, relType, Map.empty[String, Any])
        }.toSeq

        GraphNeighborhood(center, neighbors, edges)
      } else {
        GraphNeighborhood(
          Concept("", "", conceptId, "NOT_FOUND", "NOT_FOUND"),
          Seq.empty,
          Seq.empty
        )
      }
    }
  }

  /**
   * Find paths between two concepts
   */
  def findPaths(fromId: String, toId: String, maxLength: Int = 4): Seq[GraphPath] = {
    logInfo(s"Finding paths from $fromId to $toId (max length: $maxLength)")

    Neo4jUtils.withSession(driver) { session =>
      val query = s"""
                     |MATCH path = (a:Concept {id: $$fromId})-[:RELATES_TO*1..$maxLength]-(b:Concept {id: $$toId})
                     |RETURN nodes(path) AS nodes, relationships(path) AS rels
                     |LIMIT 10
      """.stripMargin

      val result = session.run(
        query,
        Values.parameters("fromId", fromId, "toId", toId)
      )


      result.list().asScala.map { record =>
        val nodes = record.get("nodes").asList().asScala.map { v =>
          nodeToConcept(v.asInstanceOf[Value].asNode())
        }.toSeq

        val edges = record.get("rels").asList().asScala.map { v =>
          val rel = v.asInstanceOf[Value].asRelationship()
          GraphEdge(
            from = rel.startNodeId().toString,
            to = rel.endNodeId().toString,
            relType = rel.`type`(),
            props = rel.asMap().asScala.toMap.map { case (k, v) => k -> v.asInstanceOf[Any] }
          )
        }.toSeq

        GraphPath(nodes, edges, nodes.size - 1)
      }.toSeq
    }
  }

  /**
   * Get graph statistics
   */
  def getStatistics(): GraphStatistics = {
    logInfo("Fetching graph statistics")

    val stats = Neo4jUtils.getStats(driver)

    GraphStatistics(
      conceptCount = stats.getOrElse("nodes:Concept", 0L),
      chunkCount = stats.getOrElse("nodes:Chunk", 0L),
      mentionsCount = stats.getOrElse("rels:MENTIONS", 0L),
      relationsCount = stats.getOrElse("rels:RELATES_TO", 0L),
      coOccurrenceCount = stats.getOrElse("rels:CO_OCCURS", 0L)
    )
  }

  /**
   * Get top concepts by connection count
   */
  def getTopConcepts(limit: Int = 10): Seq[ConceptWithScore] = {
    Neo4jUtils.withSession(driver) { session =>
      val query = """
                    |MATCH (c:Concept)-[r]-()
                    |RETURN c, count(r) AS connections
                    |ORDER BY connections DESC
                    |LIMIT $limit
      """.stripMargin

      val result = session.run(
        query,
        Values.parameters("limit", Long.box(limit))
      )


      result.list().asScala.map { record =>
        val concept = nodeToConcept(record.get("c").asNode())
        val score = record.get("connections").asLong().toDouble
        ConceptWithScore(concept, score)
      }.toSeq
    }
  }

  /**
   * Get clusters of related concepts
   */
  def getClusters(minSize: Int = 3): Seq[ConceptCluster] = {
    Neo4jUtils.withSession(driver) { session =>
      val query = """
                    |MATCH (c:Concept)-[:RELATES_TO]-(related:Concept)
                    |WITH c, collect(DISTINCT related) AS cluster
                    |WHERE size(cluster) >= $minSize
                    |RETURN c AS center, cluster
                    |LIMIT 20
      """.stripMargin

      val result = session.run(
        query,
        Values.parameters("minSize", Long.box(minSize))
      )


      result.list().asScala.map { record =>
        val center = nodeToConcept(record.get("center").asNode())
        val members = record.get("cluster").asList().asScala.map { node =>
          nodeToConcept(node.asInstanceOf[Value].asNode())
        }.toSeq
        ConceptCluster(center, members)
      }.toSeq
    }
  }

  /**
   * Search concepts by lemma pattern
   */
  def searchConcepts(pattern: String, limit: Int = 20): Seq[Concept] = {
    Neo4jUtils.withSession(driver) { session =>
      val query = """
                    |MATCH (c:Concept)
                    |WHERE c.lemma CONTAINS $pattern OR c.surface CONTAINS $pattern
                    |RETURN c
                    |LIMIT $limit
      """.stripMargin

      val result = session.run(
        query,
        Values.parameters("pattern", pattern.toLowerCase, "limit", Long.box(limit))
      )


      result.list().asScala.map { record =>
        nodeToConcept(record.get("c").asNode())
      }.toSeq
    }
  }

  private def nodeToConcept(node: org.neo4j.driver.types.Node): Concept = {
    Concept(
      conceptId = node.get("id").asString(),
      lemma = node.get("lemma").asString(),
      surface = node.get("surface").asString(),
      origin = node.get("origin").asString(),
      name = node.get("name").asString()
    )
  }
}

// Response models
case class GraphNeighborhood(
                              center: Concept,
                              neighbors: Seq[Concept],
                              edges: Seq[GraphEdge]
                            )

case class GraphEdge(
                      from: String,
                      to: String,
                      relType: String,
                      props: Map[String, Any]
                    )

case class GraphPath(
                      nodes: Seq[Concept],
                      edges: Seq[GraphEdge],
                      length: Int
                    )

case class GraphStatistics(
                            conceptCount: Long,
                            chunkCount: Long,
                            mentionsCount: Long,
                            relationsCount: Long,
                            coOccurrenceCount: Long
                          )

case class ConceptWithScore(
                             concept: Concept,
                             score: Double
                           )

case class ConceptCluster(
                           center: Concept,
                           members: Seq[Concept]
                         )

object ExploreService {
  def apply(driver: Driver): ExploreService = new ExploreService(driver)

  def apply(config: Neo4jConfig): ExploreService = {
    val driver = Neo4jUtils.createDriver(config)
    new ExploreService(driver)
  }
}

object ExploreServiceEncoders {

  // Convert Any → Json safely
  private def anyToJson(v: Any): Json = v match {
    case s: String  => Json.fromString(s)
    case n: Int     => Json.fromInt(n)
    case n: Long    => Json.fromLong(n)
    case n: Double  => Json.fromDoubleOrNull(n)
    case b: Boolean => Json.fromBoolean(b)

    // Nested Map
    case m: Map[_, _] =>
      Json.obj(
        m.collect { case (k: String, value) => k -> anyToJson(value) }.toSeq: _*
      )

    // Sequences
    case s: Seq[_] =>
      Json.arr(s.map(anyToJson): _*)

    // Fallback
    case other =>
      Json.fromString(other.toString)
  }

  // -------- GraphEdge encoder (manual because of Map[String, Any]) --------
  implicit val graphEdgeEncoder: Encoder[GraphEdge] =
    Encoder.instance { e =>
      Json.obj(
        "from"    -> Json.fromString(e.from),
        "to"      -> Json.fromString(e.to),
        "relType" -> Json.fromString(e.relType),
        "props"   -> Json.obj(
          e.props.toSeq.map { case (k, v) => k -> anyToJson(v) }: _*
        )
      )
    }

  // -------- Circe can auto-derive these --------
  implicit val conceptEncoder: Encoder[Concept] = deriveEncoder
  implicit val pathEncoder: Encoder[GraphPath] = deriveEncoder
  implicit val neighborhoodEncoder: Encoder[GraphNeighborhood] = deriveEncoder
}