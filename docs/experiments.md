# Experiments

## Concept Extraction Methods

### Heuristic (Baseline)
- Regex patterns
- Named entity recognition
- Part-of-speech tagging

### NER-based
- Stanford CoreNLP
- spaCy
- Flair

### LLM-based
- Ollama
- GPT-4
- Claude

## Relation Extraction

### Co-occurrence Only
- Window size: 3
- PMI threshold: 0.5

### Pattern-based
- Dependency parsing
- Syntactic patterns

### LLM Scoring
- Temperature: 0.0
- Min confidence: 0.65
- Batch size: 10

## Metrics
- Precision/Recall/F1
- Graph density
- Average path length
- Clustering coefficient
