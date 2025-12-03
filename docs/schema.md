# Neo4j Schema

## Nodes

### Chunk
```cypher
(:Chunk {
  chunkId: String,
  docId: String,
  text: String,
  span_start: Int,
  span_end: Int,
  sourceUri: String,
  hash: String
})
```

### Concept
```cypher
(:Concept {
  conceptId: String,
  lemma: String,
  surface: String,
  origin: String
})
```

## Relationships

### MENTIONS
```cypher
(:Chunk)-[:MENTIONS]->(:Concept)
```

### CO_OCCURS
```cypher
(:Concept)-[:CO_OCCURS {freq: Long}]->(:Concept)
```

### RELATES_TO
```cypher
(:Concept)-[:RELATES_TO {
  predicate: String,
  confidence: Double,
  evidence: String
}]->(:Concept)
```

## Example Queries

### Find high-confidence relations
```cypher
MATCH (a:Concept)-[r:RELATES_TO]->(b:Concept)
WHERE r.confidence > 0.7
RETURN a.lemma, r.predicate, b.lemma, r.confidence
ORDER BY r.confidence DESC;
```

### Find evidence for relation
```cypher
MATCH (a:Concept)-[:RELATES_TO]->(b:Concept)
MATCH (ch:Chunk)-[:MENTIONS]->(a)
MATCH (ch)-[:MENTIONS]->(b)
RETURN ch.text AS evidence;
```
