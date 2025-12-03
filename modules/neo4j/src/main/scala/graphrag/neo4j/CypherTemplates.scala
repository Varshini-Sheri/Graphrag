package graphrag.neo4j

/**
 * Centralized Cypher query templates for Neo4j operations.
 * Uses $$ to escape $ in Scala string interpolation, producing $paramName for Neo4j.
 */
object CypherTemplates {

  // ============================================
  // Node Upserts
  // ============================================

  def upsertNode(label: String): String =
    s"MERGE (n:$label {id: $$id}) SET n += $$props RETURN n"

  def upsertChunk: String =
    """MERGE (c:Chunk {id: $id})
      |SET c.chunkId = $id,
      |    c.docId = $docId,
      |    c.text = $text,
      |    c.spanStart = $spanStart,
      |    c.spanEnd = $spanEnd,
      |    c.sourceUri = $sourceUri,
      |    c.hash = $hash
      |RETURN c""".stripMargin

  def upsertConcept: String =
    """MERGE (c:Concept {id: $id})
      |SET c.conceptId = $id,
      |    c.lemma = $lemma,
      |    c.surface = $surface,
      |    c.origin = $origin,
      |    c.name = $name
      |RETURN c""".stripMargin

  // ============================================
  // Edge Upserts
  // ============================================

  def upsertEdge(fromLabel: String, rel: String, toLabel: String): String =
    s"""MERGE (a:$fromLabel {id: $$fromId})
       |MERGE (b:$toLabel {id: $$toId})
       |MERGE (a)-[r:$rel]->(b)
       |SET r += $$props
       |RETURN r""".stripMargin

  def upsertMentions: String =
    """MERGE (chunk:Chunk {id: $chunkId})
      |MERGE (concept:Concept {id: $conceptId})
      |MERGE (chunk)-[r:MENTIONS]->(concept)
      |SET r.surface = $surface
      |RETURN r""".stripMargin

  def upsertCoOccurs: String =
    """MERGE (a:Concept {id: $aId})
      |MERGE (b:Concept {id: $bId})
      |MERGE (a)-[r:CO_OCCURS]->(b)
      |ON CREATE SET r.freq = $freq, r.windowId = $windowId
      |ON MATCH SET r.freq = r.freq + $freq
      |RETURN r""".stripMargin

  def upsertRelatesTo: String =
    """MERGE (a:Concept {id: $aId})
      |MERGE (b:Concept {id: $bId})
      |MERGE (a)-[r:RELATES_TO]->(b)
      |SET r.predicate = $predicate,
      |    r.confidence = $confidence,
      |    r.evidence = $evidence
      |RETURN r""".stripMargin

  // ============================================
  // Queries
  // ============================================

  def findConcepts(limit: Int = 100): String =
    s"MATCH (c:Concept) RETURN c LIMIT $limit"

  def findConceptByLemma: String =
    "MATCH (c:Concept {lemma: $lemma}) RETURN c"

  def findRelations(minConfidence: Double): String =
    s"""MATCH (a:Concept)-[r:RELATES_TO]->(b:Concept)
       |WHERE r.confidence >= $minConfidence
       |RETURN a, r, b""".stripMargin

  def findCoOccurrences(minFreq: Int = 2): String =
    s"""MATCH (a:Concept)-[r:CO_OCCURS]->(b:Concept)
       |WHERE r.freq >= $minFreq
       |RETURN a, r, b""".stripMargin

  def findChunkMentions: String =
    """MATCH (chunk:Chunk {id: $chunkId})-[r:MENTIONS]->(c:Concept)
      |RETURN c, r""".stripMargin

  def findConceptContext: String =
    """MATCH (c:Concept {id: $conceptId})<-[:MENTIONS]-(chunk:Chunk)
      |RETURN chunk
      |ORDER BY chunk.docId
      |LIMIT $limit""".stripMargin

  // ============================================
  // Graph Traversal (for RAG queries)
  // ============================================

  def findRelatedConcepts(maxHops: Int = 2): String =
    s"""MATCH path = (start:Concept {id: $$conceptId})-[:RELATES_TO|CO_OCCURS*1..$maxHops]-(related:Concept)
       |RETURN DISTINCT related, length(path) AS distance
       |ORDER BY distance
       |LIMIT $$limit""".stripMargin

  def findConceptNeighborhood: String =
    """MATCH (c:Concept {id: $conceptId})
      |OPTIONAL MATCH (c)-[r1:RELATES_TO]->(out:Concept)
      |OPTIONAL MATCH (c)<-[r2:RELATES_TO]-(in:Concept)
      |OPTIONAL MATCH (c)-[r3:CO_OCCURS]-(co:Concept)
      |RETURN c, collect(DISTINCT out) AS outgoing,
      |       collect(DISTINCT in) AS incoming,
      |       collect(DISTINCT co) AS cooccurring""".stripMargin

  // ============================================
  // Admin / Maintenance
  // ============================================

  def createIndexes: Seq[String] = Seq(
    "CREATE INDEX chunk_id IF NOT EXISTS FOR (c:Chunk) ON (c.id)",
    "CREATE INDEX chunk_doc IF NOT EXISTS FOR (c:Chunk) ON (c.docId)",
    "CREATE INDEX concept_id IF NOT EXISTS FOR (c:Concept) ON (c.id)",
    "CREATE INDEX concept_lemma IF NOT EXISTS FOR (c:Concept) ON (c.lemma)"
  )

  def countNodes: String =
    """MATCH (n)
      |RETURN labels(n)[0] AS label, count(n) AS count
      |ORDER BY count DESC""".stripMargin

  def countRelationships: String =
    """MATCH ()-[r]->()
      |RETURN type(r) AS type, count(r) AS count
      |ORDER BY count DESC""".stripMargin
}