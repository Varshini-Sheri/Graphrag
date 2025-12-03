package graphrag.neo4j

import org.neo4j.driver._
import java.util
import scala.collection.JavaConverters._

class Neo4jClient(uri: String, user: String, password: String, database: String) {

  private val driver: Driver =
    GraphDatabase.driver(uri, AuthTokens.basic(user, password))

  private def session(): Session =
    driver.session(SessionConfig.forDatabase(database))

  // ============================================================
  // DTOs (match API layer)
  // ============================================================

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

  // ============================================================
  // 1. Substring concept search
  // ============================================================

  def findConceptsBySubstring(q: String, limit: Int): Seq[ConceptHit] = {
    val cypher =
      """MATCH (c:Concept)
        |WHERE toLower(c.lemma) CONTAINS toLower($q)
        |RETURN c.id AS id, c.lemma AS lemma
        |LIMIT $limit""".stripMargin

    val params = Map("q" -> q, "limit" -> Int.box(limit)).asJava

    val s = session()
    try {
      val result = s.run(cypher, params)
      result.list().asScala.map { r =>
        ConceptHit(
          conceptId = r.get("id").asString(),
          lemma = r.get("lemma").asString()
        )
      }.toSeq
    } finally s.close()
  }

  // ============================================================
  // 2. Top co-occurrences
  // ============================================================

  def getTopCoOccurringConcepts(conceptId: String, k: Int): Seq[NeighborHit] = {
    val cypher =
      """MATCH (:Concept {id: $id})-[r:CO_OCCURS]-(o:Concept)
        |RETURN o.id AS id, r.freq AS freq
        |ORDER BY freq DESC
        |LIMIT $k""".stripMargin

    val params = Map("id" -> conceptId, "k" -> Int.box(k)).asJava

    val s = session()
    try {
      val res = s.run(cypher, params)
      res.list().asScala.map { r =>
        NeighborHit(
          conceptId = r.get("id").asString(),
          weight = r.get("freq").asLong()
        )
      }.toSeq
    } finally s.close()
  }

  // ============================================================
  // 3. Evidence lookup
  // ============================================================

  def getEvidenceByChunkId(chunkId: String): Option[ChunkEvidence] = {
    val cypher =
      """MATCH (c:Chunk {id: $id})
        |RETURN c.id AS chunkId,
        |       c.docId AS docId,
        |       c.text AS text,
        |       c.spanStart AS spanStart,
        |       c.spanEnd AS spanEnd""".stripMargin

    val params = Map("id" -> chunkId).asJava

    val s = session()
    try {
      val rows = s.run(cypher, params).list().asScala
      if (rows.isEmpty) None
      else {
        val r = rows.head
        Some(
          ChunkEvidence(
            chunkId = r.get("chunkId").asString(),
            docId = r.get("docId").asString(),
            text =
              if (r.get("text").isNull) None else Some(r.get("text").asString()),
            spanStart = r.get("spanStart").asInt(),
            spanEnd = r.get("spanEnd").asInt()
          )
        )
      }
    } finally s.close()
  }

  // ============================================================
  // 4. Concept neighborhood
  // ============================================================

  def getConceptNeighborhood(conceptId: String, limit: Int): Option[Neighborhood] = {
    val params = Map("id" -> conceptId, "limit" -> Int.box(limit)).asJava
    val s = session()

    try {
      // Fetch center node
      val centerRows = s.run(
        "MATCH (c:Concept {id: $id}) RETURN c",
        Map("id" -> conceptId).asJava
      ).list().asScala

      if (centerRows.isEmpty) return None

      val centerNode = centerRows.head.get("c").asNode()
      val center = GraphNode(
        id = centerNode.get("id").asString(),
        label = centerNode.labels().asScala.headOption.getOrElse("Concept"),
        props = centerNode.asMap().asScala.toMap
      )

      // All neighbors
      val cypher =
        """MATCH (c:Concept {id: $id})
          |OPTIONAL MATCH (c)-[:RELATES_TO]->(out:Concept)
          |OPTIONAL MATCH (c)<-[:RELATES_TO]-(in:Concept)
          |OPTIONAL MATCH (c)-[:CO_OCCURS]->(co:Concept)
          |
          |RETURN
          |  collect(DISTINCT out) AS outNodes,
          |  collect(DISTINCT in) AS inNodes,
          |  collect(DISTINCT co) AS coNodes""".stripMargin

      val r = s.run(cypher, params).list().asScala.head

      def convert(v: Value): Seq[GraphNode] =
        v.asList((item: Value) => item).asScala.flatMap { x =>
          if (x == null || x.isNull) None
          else {
            val n = x.asNode()
            Some(
              GraphNode(
                id = n.get("id").asString(),
                label = n.labels().asScala.headOption.getOrElse("Concept"),
                props = n.asMap().asScala.toMap
              )
            )
          }
        }.toSeq

      val outNodes = convert(r.get("outNodes"))
      val inNodes  = convert(r.get("inNodes"))
      val coNodes  = convert(r.get("coNodes"))

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

  // ============================================================
  // Close driver
  // ============================================================

  def close(): Unit =
    driver.close()
}
