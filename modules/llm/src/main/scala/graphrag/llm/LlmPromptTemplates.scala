package graphrag.llm

/**
 * Centralized prompt templates for LLM interactions.
 */
object LlmPromptTemplates {

  // Valid predicates for relation extraction
  val ValidPredicates: Set[String] = Set(
    "is_a", "part_of", "causes", "caused_by",
    "synonym_of", "related_to", "uses", "used_by",
    "improves", "depends_on", "contains", "belongs_to",
    "precedes", "follows", "enables", "prevents"
  )

  def relationExtractionPrompt(conceptA: String, conceptB: String, evidence: String): String =
    s"""Analyze the semantic relationship between two concepts from a software engineering research paper.

Concept A: $conceptA
Concept B: $conceptB
Context: $evidence

Valid relationship types:
- is_a: A is a type/instance of B
- part_of: A is a component/part of B
- causes: A causes or leads to B
- synonym_of: A and B mean the same thing
- related_to: A and B are related but no specific relation
- uses: A uses or employs B
- improves: A improves or enhances B
- depends_on: A depends on or requires B
- enables: A enables or allows B
- prevents: A prevents or blocks B

Respond ONLY with valid JSON (no markdown, no explanation):
{"predicate": "relation_type", "confidence": 0.XX, "evidence": "brief explanation", "ref": "source"}"""

  def conceptExtractionPrompt(text: String): String = {
    // ADD THIS LINE
    println("🟢 LlmPromptTemplates.conceptExtractionPrompt() CALLED - IMPROVED VERSION v2.0")
    s"""Extract key technical concepts from this software engineering research text.

Text: $text

STRICT EXTRACTION RULES:

DO NOT EXTRACT:
- Author names or personal names
- Stopwords: "the", "a", "an", "then", "their", "this", "that"
- Single letters, numbers, or symbols
- Partial phrases or incomplete terms
- Hyphenated fragments (e.g., "crypto-API" - extract full term instead)
- Generic words: "method", "approach", "system" without context
- References to papers or sections (e.g., "Section 3", "Figure 2")

ONLY EXTRACT COMPLETE, MEANINGFUL CONCEPTS:
- Full technical terms: "machine learning", "neural network", "convolutional neural network"
- Complete tool names: "TensorFlow", "PyTorch", "Git", "Docker"
- Complete methodologies: "code review", "continuous integration", "test-driven development"
- Specific algorithms: "random forest", "gradient boosting", "LSTM"
- Complete metrics: "precision", "recall", "F1-score", "accuracy"
- Standard acronyms: "API", "REST", "CNN", "RNN", "NLP"
- Complete domain terms: "defect prediction", "code smell", "technical debt"

QUALITY CHECKS:
- Each concept must be 2+ words OR a well-known acronym
- Each concept must be complete and standalone
- Each concept must be relevant to software engineering or computer science

Return JSON array with 5-15 high-quality concepts:
[{"surface": "exact complete phrase", "lemma": "normalized_form", "type": "ENTITY|PROCESS|METRIC|TOOL|ALGORITHM"}]

Respond with ONLY the JSON array, no markdown formatting."""}

  def relationValidationPrompt(conceptA: String, predicate: String, conceptB: String): String =
    s"""Validate this semantic relationship:

"$conceptA" --[$predicate]--> "$conceptB"

Is this relationship valid and meaningful in software engineering context?

Respond ONLY with JSON:
{"valid": true/false, "confidence": 0.XX, "reason": "brief explanation"}"""

  def summarizeRelationsPrompt(relations: Seq[(String, String, String)]): String = {
    val relList = relations.map { case (a, p, b) => s"  - $a --[$p]--> $b" }.mkString("\n")
    s"""Summarize these concept relationships into a coherent description:

$relList

Provide a 2-3 sentence summary of the key relationships."""
  }
}