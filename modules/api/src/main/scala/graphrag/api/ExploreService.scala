package graphrag.api

import graphrag.core.model._
import graphrag.core.utils.Logging
import graphrag.neo4j.Neo4jUtils
import org.neo4j.driver.{Driver, Values}
import org.neo4j.driver.types.Node

import scala.collection.JavaConverters._
import scala.util.Try

import io.circe._
import io.circe.generic.semiauto._

import Neo4jValueHelpers._

/**
 * Service for exploring the knowledge graph structure.
 * Returns lightweight neighborhoods with pagination per specification.
 */
class ExploreService(driver: Driver) extends Logging {

  def getNeighborhood(
                       conceptId: String,
                       direction: String = "both",
                       depth: Int = 1,
                       limit: Int = 50,
                       edgeTypes: Seq[String] = Seq("RELATES_TO", "CO_OCCURS", "MENTIONS"),
                       offset: Int = 0
                     ): GraphNeighborhoodResponse = {
    logInfo(s"Exploring neighborhood of $conceptId (direction: $direction, depth: $depth)")

    Neo4jUtils.withSession(driver) { session =>
      val directionPattern = direction match {
        case "in"  => "<-"
        case "out" => "->"
        case _     => "-"
      }

      val edgeTypeFilter = edgeTypes.mkString("|")

      val query =
        s"""
           |MATCH (center:Concept {id: $$conceptId})
           |OPTIONAL MATCH path = (center)$directionPattern[r:$edgeTypeFilter*1..$depth]$directionPattern(neighbor)
           |WHERE neighbor:Concept OR neighbor:Paper OR neighbor:Chunk
           |WITH center,
           |     collect(DISTINCT neighbor) as neighbors,
           |     collect(DISTINCT {
           |       from: CASE WHEN startNode(relationships(path)[0]) = center
           |             THEN center.id
           |             ELSE startNode(relationships(path)[0]).id END,
           |       to: CASE WHEN endNode(relationships(path)[0]) = center
           |           THEN center.id
           |           ELSE endNode(relationships(path)[0]).id END,
           |       type: type(relationships(path)[0]),
           |       props: properties(relationships(path)[0])
           |     }) as edges
           |RETURN center, neighbors[$$offset..$$offset+$$limit] as paginatedNeighbors,
           |       size(neighbors) as totalNeighbors, edges
         """.stripMargin

      val result = session.run(
        query,
        Values.parameters(
          "conceptId", conceptId,
          "offset", Long.box(offset),
          "limit", Long.box(limit)
        )
      )

      if (!result.hasNext) {
        return GraphNeighborhoodResponse(
          center = GraphNode(conceptId, "Concept", Map("error" -> "NOT_FOUND")),
          nodes = Seq.empty,
          edges = Seq.empty,
          page = PageInfo(limit, Some(offset), Some(0L), None)
        )
      }

      val record     = result.next()
      val centerNode = record.get("center").asNode()
      val center = GraphNode(
        id = getNodeString(centerNode, "id"),
        label = "Concept",
        props = Map(
          "lemma"   -> getNodeString(centerNode, "lemma"),
          "surface" -> getNodeString(centerNode, "surface")
        )
      )

      // neighbors
      val neighbors = record.get("paginatedNeighbors").asList().asScala.flatMap { v =>
        extractNode(v).map { node =>
          val labels = node.labels().asScala.toSeq
          GraphNode(
            id = getNodeString(node, "id"),
            label = labels.headOption.getOrElse("Node"),
            props = extractNodeProps(node, labels.headOption.getOrElse("Node"))
          )
        }
      }.toSeq

      // edges – Try(...).toOption.getOrElse(None) so flatMap works in 2.12
      val edges = record.get("edges").asList().asScala.flatMap { v =>
        Try {
          toValue(v).map { value =>
            val map = value.asMap().asScala
            GraphEdge(
              from = map("from").toString,
              to   = map("to").toString,
              `type` = map("type").toString,
              props = map
                .get("props")
                .map { p =>
                  p.asInstanceOf[java.util.Map[String, Any]]
                    .asScala
                    .toMap
                    .map { case (k, vv) => k -> anyToJsonValue(vv) }
                }
                .getOrElse(Map.empty[String, Any])
            )
          }
        }.toOption.getOrElse(None)
      }.toSeq

      val totalNeighbors = record.get("totalNeighbors").asLong()
      val hasMore        = offset + limit < totalNeighbors
      val nextPageToken  = if (hasMore) Some(s"offset:${offset + limit}") else None

      GraphNeighborhoodResponse(
        center = center,
        nodes  = neighbors,
        edges  = edges,
        page   = PageInfo(
          limit          = limit,
          offset         = Some(offset),
          total          = Some(totalNeighbors),
          nextPageToken  = nextPageToken
        )
      )
    }
  }

  def findPaths(
                 fromId: String,
                 toId: String,
                 maxLength: Int = 4,
                 limit: Int = 10
               ): Seq[GraphPath] = {
    logInfo(s"Finding paths from $fromId to $toId (max length: $maxLength)")

    Neo4jUtils.withSession(driver) { session =>
      val query =
        s"""
           |MATCH path = allShortestPaths(
           |  (a:Concept {id: $$fromId})-[:RELATES_TO|CO_OCCURS*1..$maxLength]-
           |  (b:Concept {id: $$toId})
           |)
           |WITH path, length(path) as pathLength
           |ORDER BY pathLength
           |LIMIT $$limit
           |RETURN nodes(path) AS nodes, relationships(path) AS rels, pathLength
         """.stripMargin

      val result = session.run(
        query,
        Values.parameters("fromId", fromId, "toId", toId, "limit", Long.box(limit))
      )

      result.list().asScala.map { record =>
        val nodes = record.get("nodes").asList().asScala.flatMap { v =>
          extractNode(v).map { n =>
            val labels = n.labels().asScala.toSeq
            GraphNode(
              id = getNodeString(n, "id"),
              label = labels.headOption.getOrElse("Node"),
              props = extractNodeProps(n, labels.headOption.getOrElse("Node"))
            )
          }
        }.toSeq

        val edges = record.get("rels").asList().asScala.flatMap { v =>
          Try {
            toValue(v).map { value =>
              val rel = value.asRelationship()
              GraphEdge(
                from  = rel.startNodeId().toString,
                to    = rel.endNodeId().toString,
                `type` = rel.`type`(),
                props  = rel.asMap().asScala.toMap.map {
                  case (k, vv) => k -> anyToJsonValue(vv)
                }
              )
            }
          }.toOption.getOrElse(None)
        }.toSeq

        GraphPath(
          nodes  = nodes,
          edges  = edges,
          length = record.get("pathLength").asInt()
        )
      }.toSeq
    }
  }

  def getStatistics(): GraphStatistics = {
    logInfo("Fetching graph statistics")

    val stats = Neo4jUtils.getStats(driver)

    GraphStatistics(
      conceptCount      = stats.getOrElse("nodes:Concept", 0L),
      paperCount        = stats.getOrElse("nodes:Paper", 0L),
      chunkCount        = stats.getOrElse("nodes:Chunk", 0L),
      datasetCount      = stats.getOrElse("nodes:Dataset", 0L),
      mentionsCount     = stats.getOrElse("rels:MENTIONS", 0L),
      relationsCount    = stats.getOrElse("rels:RELATES_TO", 0L),
      coOccurrenceCount = stats.getOrElse("rels:CO_OCCURS", 0L),
      hasChunkCount     = stats.getOrElse("rels:HAS_CHUNK", 0L)
    )
  }

  def getTopConcepts(limit: Int = 10, sortBy: String = "connections"): Seq[ConceptWithScore] = {
    Neo4jUtils.withSession(driver) { session =>
      val sortClause = sortBy match {
        case "mentions" => "mentionCount"
        case "papers"   => "paperCount"
        case _          => "connections"
      }

      val query =
        s"""
           |MATCH (c:Concept)
           |OPTIONAL MATCH (c)-[r]-()
           |OPTIONAL MATCH (c)<-[:MENTIONS]-(chunk:Chunk)
           |OPTIONAL MATCH (chunk)<-[:HAS_CHUNK]-(p:Paper)
           |WITH c, count(DISTINCT r) AS connections,
           |        count(DISTINCT chunk) AS mentionCount,
           |        count(DISTINCT p) AS paperCount
           |RETURN c, connections, mentionCount, paperCount
           |ORDER BY $sortClause DESC
           |LIMIT $$limit
         """.stripMargin

      val result = session.run(
        query,
        Values.parameters("limit", Long.box(limit))
      )

      result.list().asScala.map { record =>
        val node = record.get("c").asNode()
        val score = sortBy match {
          case "mentions" => record.get("mentionCount").asLong().toDouble
          case "papers"   => record.get("paperCount").asLong().toDouble
          case _          => record.get("connections").asLong().toDouble
        }

        ConceptWithScore(
          concept = Concept(
            conceptId = getNodeString(node, "id"),
            lemma     = getNodeString(node, "lemma"),
            surface   = getNodeString(node, "surface"),
            origin    = getNodeString(node, "origin"),
            name      = getNodeString(node, "name")
          ),
          score     = score,
          scoreType = sortBy
        )
      }.toSeq
    }
  }

  def searchConcepts(
                      pattern: String,
                      limit: Int = 20,
                      offset: Int = 0
                    ): ConceptSearchResponse = {
    Neo4jUtils.withSession(driver) { session =>
      val countQuery =
        """
          |MATCH (c:Concept)
          |WHERE toLower(c.lemma)   CONTAINS toLower($pattern)
          |   OR toLower(c.surface) CONTAINS toLower($pattern)
          |RETURN count(c) as total
        """.stripMargin

      val countResult = session.run(
        countQuery,
        Values.parameters("pattern", pattern)
      )
      val total =
        if (countResult.hasNext) countResult.next().get("total").asLong()
        else 0L

      val query =
        """
          |MATCH (c:Concept)
          |WHERE toLower(c.lemma)   CONTAINS toLower($pattern)
          |   OR toLower(c.surface) CONTAINS toLower($pattern)
          |RETURN c
          |ORDER BY c.surface
          |SKIP $offset
          |LIMIT $limit
        """.stripMargin

      val result = session.run(
        query,
        Values.parameters(
          "pattern", pattern,
          "offset",  Long.box(offset),
          "limit",   Long.box(limit)
        )
      )

      val concepts = result.list().asScala.map { record =>
        val node = record.get("c").asNode()
        Concept(
          conceptId = getNodeString(node, "id"),
          lemma     = getNodeString(node, "lemma"),
          surface   = getNodeString(node, "surface"),
          origin    = getNodeString(node, "origin"),
          name      = getNodeString(node, "name")
        )
      }.toSeq

      val hasMore       = offset + limit < total
      val nextPageToken = if (hasMore) Some(s"offset:${offset + limit}") else None

      ConceptSearchResponse(
        concepts = concepts,
        page = PageInfo(
          limit         = limit,
          offset        = Some(offset),
          total         = Some(total),
          nextPageToken = nextPageToken
        )
      )
    }
  }

  private def extractNodeProps(node: Node, label: String): Map[String, Any] =
    Try {
      label match {
        case "Concept" =>
          Map[String, Any](
            "lemma"   -> getNodeString(node, "lemma"),
            "surface" -> getNodeString(node, "surface")
          )
        case "Paper" =>
          Map[String, Any](
            "title" -> getNodeString(node, "title"),
            "year"  -> getNodeInt(node, "year")
          )
        case "Dataset" =>
          Map[String, Any]("name" -> getNodeString(node, "name"))
        case _ =>
          Map.empty[String, Any]
      }
    }.getOrElse(Map.empty[String, Any])
}

/** ---------- Response models ---------- */

case class GraphNeighborhoodResponse(
                                      center: GraphNode,
                                      nodes:  Seq[GraphNode],
                                      edges:  Seq[GraphEdge],
                                      page:   PageInfo
                                    )

case class GraphNode(
                      id:    String,
                      label: String,
                      props: Map[String, Any]
                    )

case class GraphEdge(
                      from:  String,
                      to:    String,
                      `type`: String,
                      props: Map[String, Any]
                    )

case class GraphPath(
                      nodes:  Seq[GraphNode],
                      edges:  Seq[GraphEdge],
                      length: Int
                    )

case class PageInfo(
                     limit:         Int,
                     offset:        Option[Int]  = None,
                     total:         Option[Long] = None,
                     nextPageToken: Option[String] = None
                   )

case class GraphStatistics(
                            conceptCount:      Long,
                            paperCount:        Long,
                            chunkCount:        Long,
                            datasetCount:      Long,
                            mentionsCount:     Long,
                            relationsCount:    Long,
                            coOccurrenceCount: Long,
                            hasChunkCount:     Long
                          )

case class ConceptWithScore(
                             concept:   Concept,
                             score:     Double,
                             scoreType: String
                           )

case class ConceptSearchResponse(
                                  concepts: Seq[Concept],
                                  page:     PageInfo
                                )

/** ---------- Encoders ---------- */

object ExploreServiceEncoders {

  private def anyToJson(v: Any): Json = v match {
    case s: String  => Json.fromString(s)
    case n: Int     => Json.fromInt(n)
    case n: Long    => Json.fromLong(n)
    case n: Double  => Json.fromDoubleOrNull(n)
    case b: Boolean => Json.fromBoolean(b)
    case null       => Json.Null
    case m: Map[_, _] =>
      Json.obj(
        m.asInstanceOf[Map[String, Any]].toSeq.map {
          case (k, value) => k -> anyToJson(value)
        }: _*
      )
    case other => Json.fromString(other.toString)
  }

  implicit val graphNodeEncoder: Encoder[GraphNode] = Encoder.instance { node =>
    Json.obj(
      "id"    -> Json.fromString(node.id),
      "label" -> Json.fromString(node.label),
      "props" -> Json.obj(
        node.props.toSeq.map { case (k, v) => k -> anyToJson(v) }: _*
      )
    )
  }

  implicit val graphEdgeEncoder: Encoder[GraphEdge] = Encoder.instance { edge =>
    Json.obj(
      "from"  -> Json.fromString(edge.from),
      "to"    -> Json.fromString(edge.to),
      "type"  -> Json.fromString(edge.`type`),
      "props" -> Json.obj(
        edge.props.toSeq.map { case (k, v) => k -> anyToJson(v) }: _*
      )
    )
  }

  implicit val conceptEncoder: Encoder[Concept] = Encoder.instance { c =>
    Json.obj(
      "conceptId" -> Json.fromString(c.conceptId),
      "lemma"     -> Json.fromString(c.lemma),
      "surface"   -> Json.fromString(c.surface),
      "origin"    -> Json.fromString(c.origin)
    )
  }

  implicit val pageInfoEncoder:                Encoder[PageInfo]                = deriveEncoder
  implicit val graphPathEncoder:               Encoder[GraphPath]               = deriveEncoder
  implicit val neighborhoodEncoder:            Encoder[GraphNeighborhoodResponse] = deriveEncoder
  implicit val statisticsEncoder:              Encoder[GraphStatistics]         = deriveEncoder
  implicit val conceptWithScoreEncoder:        Encoder[ConceptWithScore]        = deriveEncoder
  implicit val conceptSearchResponseEncoder:   Encoder[ConceptSearchResponse]   = deriveEncoder
}

/** ---------- Companion ---------- */

object ExploreService {
  def apply(driver: Driver): ExploreService =
    new ExploreService(driver)
}
