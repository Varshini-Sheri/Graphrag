# GraphRAG - Graph-based Retrieval Augmented Generation

Multi-module Scala + Flink + Neo4j system for building knowledge graphs.

## Structure

```
graphrag/
├── modules/
│   ├── core/         # Domain models, config, utils
│   ├── ingestion/    # Data ingestion (Index, Normalize, Concept, Relations)
│   ├── llm/          # Ollama LLM integration
│   ├── neo4j/        # Neo4j sink and graph operations
│   ├── api/          # REST API services
│   └── job/          # Main Flink job
├── deploy/           # Kubernetes/Docker configs
└── docs/             # Documentation
```

## Quick Start

### Build
```bash
sbt clean compile
sbt test
```

### Run
```bash
sbt "job/run"
```

### Create JAR
```bash
sbt job/assembly
```

## Configuration

All settings in `deploy/application.conf`:
- Ollama endpoint: `http://localhost:11434`
- Neo4j URI: `bolt://localhost:7687`
- Index source: `data/index/chunks.jsonl`

## Module Commands

```bash
# Compile specific module
sbt core/compile
sbt ingestion/compile

# Test specific module
sbt core/test

# Run job
sbt "job/run"
```

## Documentation

- [Architecture](docs/architecture.md)
- [Data Flow](docs/dataflow.md)
- [Components](docs/components.md)
- [Prompts](docs/prompts.md)
- [Schema](docs/schema.md)
- [Experiments](docs/experiments.md)

## Development

Each module is self-contained with src/main and src/test.

## Deployment

### Docker
```bash
bash cd deploy
     docker-compose -d up -build
```

### Kubernetes
```bash
bash deploy/scripts/deploy.sh
```

See [deploy/eks-cluster-setup.txt](deploy/eks-cluster-setup.txt) for EKS setup.

## License

Educational project for CS441.
