# Component Details

## Core Module
- **Chunk**: Document chunk model
- **Concept**: Entity/concept model  
- **Relations**: Mentions, CoOccur, ScoredRelation
- **GraphWrite**: Node/edge operations
- **Utils**: Hashing, logging, IDs
- **Config**: Configuration loading

## Ingestion Module
- **IndexSource**: Base source
- **S3IndexSource**: S3 storage
- **LocalIndexSource**: Local files
- **Normalize**: Text normalization
- **ConceptStage**: Concept extraction
- **RelationStage**: Relation extraction

## LLM Module
- **OllamaClient**: LLM client
- **LLMStage**: Flink integration
- **PromptTemplates**: Prompt generation
- **Verdict**: Response parsing

## Neo4j Module
- **Neo4jSink**: Batch writer
- **GraphUpsert**: Cypher generation
- **CypherTemplates**: Query templates

## API Module
- **HttpServer**: REST server
- **QueryService**: Graph queries
- **EvidenceService**: Evidence retrieval
- **ExploreService**: Graph exploration

## Job Module
- **Main**: Entry point
- **GraphRagJob**: Job orchestration
- **PipelineBuilder**: Pipeline construction
- **GraphProjector**: Graph projection
- **Quality**: Quality metrics
