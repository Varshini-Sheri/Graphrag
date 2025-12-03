# Data Flow

## Pipeline Stages

### 1. Index Reading
- IndexSource reads chunks from JSONL
- S3IndexSource for S3 storage
- LocalIndexSource for local files

### 2. Normalization
- Remove extra whitespace
- Standardize encoding
- Clean special characters

### 3. Concept Extraction
- Heuristic extraction
- Pattern-based NER
- Surface form identification

### 4. Co-occurrence
- Sliding window analysis
- PMI filtering
- Frequency counting

### 5. Relation Extraction
- Generate candidates
- LLM scoring
- Confidence filtering

### 6. Graph Projection
- Convert to GraphWrite ops
- Create nodes and edges
- Batch operations

### 7. Neo4j Writing
- Batch upserts
- MERGE semantics
- Retry logic
