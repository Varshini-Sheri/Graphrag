# LLM Prompts

## Relation Extraction

### Template
```
Given two concepts and context, determine their relation.

Concept A: {concept_a}
Concept B: {concept_b}
Context: {evidence}

Determine if there is a relation. Choose from:
- is_a: A is a type of B
- part_of: A is part of B
- causes: A causes B
- synonym_of: A is a synonym of B
- related_to: A is generally related to B
- none: No clear relation

Respond with JSON:
{
  "predicate": "relation_type",
  "confidence": 0.0-1.0,
  "evidence": "brief explanation",
  "ref": "evidence_ref"
}
```

## Prompt Engineering Tips
- Keep prompts concise
- Provide clear examples
- Use structured output (JSON)
- Specify confidence scores
- Include evidence requirements
