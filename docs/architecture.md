# GraphRAG Architecture

## Overview
GraphRAG processes documents into a knowledge graph using Apache Flink and Neo4j.

## Components
- **Core**: Domain models, utilities, configuration
- **Ingestion**: Data reading and processing
- **LLM**: Ollama integration for relation extraction
- **Neo4j**: Graph database sink
- **API**: REST endpoints for querying
- **Job**: Main Flink pipeline

## Data Flow
1. Read chunks from index
2. Normalize text
3. Extract concepts
4. Compute co-occurrences
5. Score relations with LLM
6. Project to graph operations
7. Write to Neo4j
