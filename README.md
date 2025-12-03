# GraphRAG

**Course:** CS441 – Distributed & Cloud Computing, Fall 2025  
**Institution:** University of Illinois Chicago (UIC)  
**Author:** Varshini Sheri  
**Email:** ssher9@uic.edu

---

## Project Description

This project implements a **Graph-based Retrieval Augmented Generation (GraphRAG)** pipeline for analyzing Mining Software Repositories (MSR) conference papers. Built on Apache Flink streaming architecture, the system extracts concepts, relations, and research metadata from academic documents, constructs a knowledge graph, and persists it to Neo4j for semantic querying.

### Key Features

- **Streaming Pipeline**: Apache Flink-based ingestion and transformation
- **LLM Integration**: Ollama-powered relation extraction and concept refinement
- **Knowledge Graph**: Neo4j storage with versioned nodes and typed edges
- **RESTful API**: Query, evidence retrieval, and graph exploration endpoints
- **Idempotent Upserts**: Safe replay and incremental updates
- **Cloud-Ready**: Docker Compose for local development, EKS-ready for production

---

## Architecture Overview

### Core Entity Types

```scala
case class TaskEntity(id: String, name: String)
case class DatasetEntity(id: String, name: String)
case class TechniqueEntity(id: String, name: String, family: String)
case class MetricEntity(id: String, name: String, value: Double)
case class BaselineEntity(id: String, name: String, delta: Double, metric: String)
```

### Pipeline Flow

```
┌─────────────────────────────────────────────────────────────┐
│              INPUT: Documents & Chunks Stream                │
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
         ┌────────────────────────────────────┐
         │    MetadataExtractors              │
         │    - Paper nodes from documents     │
         │    - Chunk nodes from text          │
         └────────────────────────────────────┘
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
    ┌─────────┐    ┌──────────┐   ┌─────────────┐
    │ Concept │    │ Mentions │   │  Metadata   │
    │  Stage  │    │  Stage   │   │   Stage     │
    └─────────┘    └──────────┘   └─────────────┘
          │               │               │
          ▼               ▼               ▼
    [Concept Nodes]  [MENTIONS]   [Task, Dataset,
                      Edges        Technique, etc.]
          │               │               │
          └───────┬───────┴───────┬───────┘
                  ▼               ▼
         ┌──────────────┐  ┌──────────────┐
         │ Co-Occur     │  │  Relation    │
         │  Analysis    │  │  Candidates  │
         └──────────────┘  └──────────────┘
                  │               │
                  └───────┬───────┘
                          ▼
                  ┌───────────────┐
                  │   LLM Stage   │
                  │   (Ollama)    │
                  │  RELATES_TO   │
                  └───────────────┘
                          │
                          ▼
                 ┌─────────────────┐
                 │ GraphProjector  │
                 │  Union Streams  │
                 └─────────────────┘
                          │
                          ▼
                  ┌───────────────┐
                  │  Neo4j Sink   │
                  │  (batch 200)  │
                  └───────────────┘
                          │
                          ▼
                    ┌──────────┐
                    │  Neo4j   │
                    └──────────┘
```

### Graph Schema

**Nodes**

| Label       | Key Properties                          |
|-------------|-----------------------------------------|
| `Paper`     | `id`, `title`, `year`, `venue`, `url`   |
| `Chunk`     | `id`, `docId`, `text`, `span`, `hash`   |
| `Concept`   | `id`, `lemma`, `surface`, `origin`      |
| `Task`      | `id`, `name`                            |
| `Dataset`   | `id`, `name`                            |
| `Technique` | `id`, `name`, `family`                  |
| `Metric`    | `id`, `name`, `value`                   |
| `Baseline`  | `id`, `name`, `delta`, `metric`         |

**Edges**

| Type            | From → To         | Properties                           |
|-----------------|-------------------|--------------------------------------|
| `MENTIONS`      | Chunk → Concept   | `surface`                            |
| `CO_OCCURS`     | Concept ↔ Concept | `freq`, `windowId`                   |
| `RELATES_TO`    | Concept → Concept | `predicate`, `confidence`, `evidence`|
| `BELONGS_TO`    | Chunk → Paper     | —                                    |
| `ADDRESSES`     | Paper → Task      | —                                    |
| `USES_DATASET`  | Paper → Dataset   | —                                    |
| `PROPOSES`      | Paper → Technique | —                                    |
| `REPORTS`       | Paper → Metric    | —                                    |
| `IMPROVES_OVER` | Paper → Baseline  | `delta`, `metric`                    |

---

## Installation & Prerequisites

### Requirements

- **Java**: 17+
- **Scala**: 2.12.18
- **sbt**: 1.10+
- **Apache Flink**: 1.18.x
- **Neo4j**: 5.x (Aura or local)
- **Ollama**: Latest with `llama3-instruct` model
- **Docker**: 20.10+ & Docker Compose 2.x

### Clone Repository

```bash
git clone git@github.com:Varshini-Sheri/Graphrag.git
cd Graphrag
```

### Prepare Data

Place your MSRCorpus index files:

```
data/index/chunks.jsonl
data/index/documents.jsonl
```

---

## Configuration

### Local Configuration

Copy and edit the configuration:

```bash
cd deploy/docker
cp application.conf.example application.conf
```

Key settings in `application.conf`:

```hocon
neo4j {
  uri      = "neo4j+s://your-instance.databases.neo4j.io"
  user     = "neo4j"
  password = "your-password"
  database = "neo4j"
}

ollama {
  endpoint    = "http://ollama:11434"
  model       = "llama3-instruct"
  temperature = 0.0
  timeoutMs   = 15000
}

index {
  documents = "/app/data/index/documents.jsonl"
  chunks    = "/app/data/index/chunks.jsonl"
  format    = "jsonl"
}
```

### Docker Compose Setup

The `docker-compose.yml` orchestrates:

- **Ollama**: LLM inference service (port 11434)
- **GraphRAG Job**: Flink streaming pipeline
- **GraphRAG API**: REST API server (port 8080)

Environment variables are injected for Neo4j and Ollama endpoints.

---

## Build Instructions

### Clean Build

```bash
sbt clean compile
```

### Run Tests

```bash
# All tests
sbt test

# Module-specific tests
sbt "project core" test
sbt "project ingestion" test
sbt "project llm" test
sbt "project neo4j" test
sbt "project job" test
```

### Build Fat JARs

```bash
# Build both job and API assemblies
sbt "job/assembly" "api/assembly"
```

Output locations:
- Job: `modules/job/target/scala-2.12/graphrag-job-assembly-0.1.0-SNAPSHOT.jar`
- API: `modules/api/target/scala-2.12/graphrag-api-assembly-0.1.0-SNAPSHOT.jar`

---

## Local Run with Docker

### 1. Build JARs

```bash
sbt clean "job/assembly" "api/assembly"
```

### 2. Start Services

```bash
cd deploy/
docker-compose up -d --build
```

This starts:
- **Ollama** on `http://localhost:11434`
- **GraphRAG Job** (runs once, processes index)
- **GraphRAG API** on `http://localhost:8080`

### 3. Monitor Logs

```bash
# View all logs
docker-compose logs -f

# Specific service logs
docker logs graphrag-job
docker logs graphrag-api
docker logs ollama
```

### 4. Verify Neo4j

Open Neo4j Browser at your Aura instance URL or local `http://localhost:7474`:

```cypher
// Check node counts
MATCH (n) 
RETURN labels(n)[0] AS label, count(*) AS count 
ORDER BY count DESC;

// Sample concepts
MATCH (c:Concept) 
RETURN c.lemma, c.surface 
LIMIT 10;
```

---

## API Usage

### Health Check

```bash
curl http://localhost:8080/v1/health
```

### Graph Statistics

```bash
curl http://localhost:8080/v1/graph/statistics
```

### Search Concepts

```bash
curl "http://localhost:8080/v1/graph/search?pattern=machine&limit=10"
```

### Concept Neighborhood

```bash
# Replace {conceptId} with actual ID from Neo4j
curl "http://localhost:8080/v1/graph/concept/c_01812109bada280e/neighbors?depth=2"
```

### Natural Language Query

```bash
curl -X POST http://localhost:8080/v1/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What techniques improve defect prediction?",
    "maxResults": 10
  }'
```

### Explain Concept

```bash
curl "http://localhost:8080/v1/explain/concept/c_01812109bada280e"
```

---

## Project Structure

```
graphrag/
├── modules/
│   ├── core/         # Domain models, config, logging
│   ├── ingestion/    # Flink stages (Index, Concept, Relations)
│   ├── llm/          # Ollama client & prompts
│   ├── neo4j/        # Graph upsert, Cypher templates, sink
│   ├── api/          # Akka HTTP REST services
│   └── job/          # Main Flink job orchestration
├── data/
│   └── index/        # chunks.jsonl, documents.jsonl
├── deploy/
│   ├── docker/       # Dockerfile, docker-compose.yml, configs
│   └── scripts/      # EKS deployment manifests 
├── docs/             # Architecture, prompts, schema docs
└── build.sbt         # Multi-module build configuration
```

---

## Troubleshooting

### API Won't Start

```bash
# Check port availability
netstat -ano | findstr :8080

# Check logs
docker logs graphrag-api

# Or run locally
sbt "api/run"
```

### Empty Query Results

Verify graph has data:

```cypher
MATCH (c:Concept) 
RETURN count(c);
```

If zero, check:
1. Job logs: `docker logs graphrag-job`
2. Index files present in `data/index/`
3. Neo4j credentials in environment variables

### Ollama Model Missing

```bash
# Pull model manually
docker exec -it ollama ollama pull llama3-instruct
```

---

## Deployment 

### AWS EKS

See `deploy/scripts/` for:
- `eks-cluster-setup.txt` - Cluster provisioning
- `flink-values.yaml` - Flink operator config
- `job-graph-rag.yaml` - Flink Application resource
- `ollama-daemonset.yaml` - Ollama pods

```bash
# Install Flink operator
helm repo add flink https://downloads.apache.org/flink/flink-kubernetes-operator-helm
helm install graphrag flink/flink-kubernetes-operator -f deploy/flink-values.yaml

# Deploy job
kubectl apply -f deploy/job-graph-rag.yaml
kubectl apply -f deploy/ollama-daemonset.yaml
```

---

## How This Relates to CS441 Homework 3

This implementation fulfills the HW3 requirements:

✅ **Streaming Engine**: Apache Flink DataStream for scalable processing  
✅ **LLM Integration**: Ollama for relation extraction and concept refinement  
✅ **Graph Store**: Neo4j with idempotent MERGE operations  
✅ **Versioned Schema**: Stable IDs for chunks and concepts  
✅ **RESTful Services**: Query, evidence, and exploration endpoints  
✅ **Cloud Deployment**: Docker Compose locally, EKS-ready for production

Builds on HW1 (indexing) and HW2 (freshness) to create a complete GraphRAG pipeline with explainable, evidence-backed answers.

---

## Running Code

```bash
# From repository root

# 1. Build and test
sbt clean compile test

# 2. Build fat JARs
sbt "job/assembly" "api/assembly"

# 3. Start full stack
cd deploy/
docker-compose up -d --build

# 4. Verify (wait ~2 minutes for job completion)
docker logs graphrag-job
curl http://localhost:8080/v1/health
curl http://localhost:8080/v1/graph/statistics
```

**Access Points:**
- API: `http://localhost:8080`
- Ollama: `http://localhost:11434`
- Neo4j: Your Aura instance or `http://localhost:7474`

---

## License & Academic Integrity

Educational project for CS441. Follow course policies on individual work and collaboration. Do not share implementation details before submission deadline.

---

## Contact

**Varshini Sheri**  
ssher9@uic.edu  
University of Illinois Chicago
