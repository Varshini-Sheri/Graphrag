package graphrag.neo4j

import org.neo4j.driver._
import java.util
import scala.jdk.CollectionConverters._

/**
 * Neo4j client that uses ONLY the Java API of the official Neo4j driver.
 * All queries are executed through standard Java driver calls.
 */
class Neo4jClient(uri: String, user: String, password: String, database: String) {

  private val driver: Driver =
    GraphDatabase.driver(uri, AuthTokens.basic(user, password))

  private def session(): Session =
    driver.session(SessionConfig.forDatabase(database))

  // --------------------------------------------------------------------
  // === DTOs used by API Layer ===
  // --------------------------------------------------------------------

  final case class ConceptHit(conceptId: String, lemma: String)
  final case class NeighborHit(conceptId: String, weight: Long)

  final case class GraphNode(id: String, label: String, props: Map[String, Any])
  final case class GraphEdge(from: String, to: String, relType: String)

  final case class Neighborhood(
                                 center: GraphNode,
                                 nodes: Seq[GraphNode],
                                 edges: Seq[GraphEdge]
                               )

  final case class ChunkEvidence(
                                  chunkId: String,
                                  docId: String,
                                  text: Option[String],
                                  spanStart: Int,
                                  spanEnd: Int
                                )

  // --------------------------------------------------------------------
  // QUERY 1: Concept substring search
  // --------------------------------------------------------------------

  def findConceptsBySubstring(q: String, limit: Int): Seq[ConceptHit] = {
    val cypher =
      """MATCH (c:Concept)
        |WHERE toLower(c.lemma) CONTAINS toLower($q)
        |RETURN c.id AS id, c.lemma AS lemma
        |LIMIT $limit""".stripMargin

    val params: util.Map[String, Object] =
      Map(
        "q" -> q,
        "limit" -> Int.box(limit)
      ).asJava.asInstanceOf[util.Map[String, Object]]

    val s = session()
    try {
      val result = s.run(cypher, params)
      result.list().asScala.map { rec =>
        ConceptHit(
          conceptId = rec.get("id").asString(),
          lemma = rec.get("lemma").asString()
        )
      }.toSeq
    } finally s.close()
  }

  // --------------------------------------------------------------------
  // QUERY 2: Co-occurrence expansion
  // --------------------------------------------------------------------

  def getTopCoOccurringConcepts(conceptId: String, k: Int): Seq[NeighborHit] = {
    val cypher =
      """MATCH (:Concept {id: $id})-[r:CO_OCCURS]-(other:Concept)
        |RETURN other.id AS id, r.freq AS freq
        |ORDER BY freq DESC
        |LIMIT $k""".stripMargin

    val params: util.Map[String, Object] =
      Map(
        "id" -> conceptId,
        "k" -> Int.box(k)
      ).asJava.asInstanceOf[util.Map[String, Object]]

    val s = session()
    try {
      val result = s.run(cypher, params)
      result.list().asScala.map { rec =>
        NeighborHit(
          conceptId = rec.get("id").asString(),
          weight = rec.get("freq").asLong()
        )
      }.toSeq
    } finally s.close()
  }

  // --------------------------------------------------------------------
  // QUERY 3: Evidence lookup
  // --------------------------------------------------------------------

  def getEvidenceByChunkId(chunkId: String): Option[ChunkEvidence] = {
    val cypher =
      """MATCH (c:Chunk {id: $id})
        |RETURN c.id as chunkId,
        |       c.docId as docId,
        |       c.text as text,
        |       c.spanStart as spanStart,
        |       c.spanEnd as spanEnd""".stripMargin

    val params: util.Map[String, Object] =
      Map("id" -> chunkId).asJava.asInstanceOf[util.Map[String, Object]]

    val s = session()
    try {
      val rows = s.run(cypher, params).list().asScala
      if (rows.isEmpty) return None

      val rec = rows.head
      Some(
        ChunkEvidence(
          chunkId = rec.get("chunkId").asString(),
          docId = rec.get("docId").asString(),
          text =
            if (rec.get("text").isNull) None
            else Some(rec.get("text").asString()),
          spanStart = rec.get("spanStart").asInt(),
          spanEnd = rec.get("spanEnd").asInt()
        )
      )
    } finally s.close()
  }

  // --------------------------------------------------------------------
  // QUERY 4: Concept Neighborhood
  // --------------------------------------------------------------------

  def getConceptNeighborhood(conceptId: String, limit: Int): Option[Neighborhood] = {
    val params: util.Map[String, Object] =
      Map("id" -> conceptId).asJava.asInstanceOf[util.Map[String, Object]]

    val s = session()

    try {
      // Fetch center node
      val centerQuery = "MATCH (c:Concept {id: $id}) RETURN c"
      val centerRows = s.run(centerQuery, params).list().asScala
      if (centerRows.isEmpty) return None

      val centerNodeValue = centerRows.head.get("c").asNode()
      val center = GraphNode(
        id = centerNodeValue.get("id").asString(),
        label = centerNodeValue.labels().asScala.headOption.getOrElse("Concept"),
        props = centerNodeValue.asMap().asScala.toMap
      )

      // Neighborhood query
      val neighCypher =
        """MATCH (c:Concept {id: $id})
          |
          |OPTIONAL MATCH (c)-[:RELATES_TO]->(out:Concept)
          |OPTIONAL MATCH (c)<-[:RELATES_TO]-(in:Concept)
          |OPTIONAL MATCH (c)-[:CO_OCCURS]-(co:Concept)
          |
          |RETURN
          |  collect(DISTINCT out) AS outNodes,
          |  collect(DISTINCT in) AS inNodes,
          |  collect(DISTINCT co) AS coNodes
        """.stripMargin

      val r = s.run(neighCypher, params).list().asScala.head

      // Convert nodes
      def convertNodes(v: Value): Seq[GraphNode] = {
        val list = v.asList((item: Value) => item).asScala
        list.flatMap { nodeValue =>
          if (nodeValue == null || nodeValue.isNull) None
          else {
            val n = nodeValue.asNode()
            Some(
              GraphNode(
                id = n.get("id").asString(),
                label = n.labels().asScala.headOption.getOrElse("Concept"),
                props = n.asMap().asScala.toMap
              )
            )
          }
        }.toSeq
      }

      val outNodes = convertNodes(r.get("outNodes"))
      val inNodes  = convertNodes(r.get("inNodes"))
      val coNodes  = convertNodes(r.get("coNodes"))

      val edges =
        outNodes.map(n => GraphEdge(center.id, n.id, "RELATES_TO")) ++
          inNodes.map(n => GraphEdge(n.id, center.id, "RELATES_TO")) ++
          coNodes.map(n => GraphEdge(center.id, n.id, "CO_OCCURS"))

      Some(
        Neighborhood(
          center = center,
          nodes = outNodes ++ inNodes ++ coNodes,
          edges = edges
        )
      )

    } finally s.close()
  }

  // --------------------------------------------------------------------
  // CLOSE
  // --------------------------------------------------------------------

  def close(): Unit =
    driver.close()
}
