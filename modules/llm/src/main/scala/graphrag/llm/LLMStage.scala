  package graphrag.llm

  import graphrag.core.model.{RelationCandidate, ScoredRelation}
  import graphrag.core.config.OllamaConfig
  import graphrag.core.utils.Logging

  import org.apache.flink.streaming.api.functions.ProcessFunction
  import org.apache.flink.configuration.Configuration
  import org.apache.flink.util.Collector

  /**
   * Flink ProcessFunction that scores relation candidates using Ollama LLM.
   * Handles client lifecycle and provides fallback for failures.
   */
  class LLMStage(config: OllamaConfig)
    extends ProcessFunction[RelationCandidate, ScoredRelation]
      with Logging {

    @transient private var client: OllamaClient = _

    override def open(parameters: Configuration): Unit = {
      super.open(parameters)
      client = OllamaClient(config)
      logInfo(s"LLMStage initialized with model: ${config.model}")
    }

    override def processElement(
                                 candidate: RelationCandidate,
                                 ctx: ProcessFunction[RelationCandidate, ScoredRelation]#Context,
                                 out: Collector[ScoredRelation]
                               ): Unit = {

      try {
        client.scoreRelation(candidate) match {
          case Some(v) =>
            out.collect(
              ScoredRelation(
                a = candidate.a,
                predicate = v.predicate,
                b = candidate.b,
                confidence = v.confidence,
                evidence = v.evidence
              )
            )
          case None =>
            logWarn(s"No verdict returned for ${candidate.a.lemma} → ${candidate.b.lemma}")
        }
      } catch {
        case e: Exception =>
          logError(s"LLM scoring failed: ${e.getMessage}")
      }
    }

    override def close(): Unit = {
      super.close()
      logInfo("LLMStage closed")
    }
  }

  object LLMStage {

    def apply(config: OllamaConfig): LLMStage = new LLMStage(config)

    /**
     * Scala functional helper (kept just in case)
     */
    def scoreRelation(config: OllamaConfig)(candidate: RelationCandidate): Option[ScoredRelation] = {
      val client = OllamaClient(config)
      client.scoreRelation(candidate).map { v =>
        ScoredRelation(
          a = candidate.a,
          predicate = v.predicate,
          b = candidate.b,
          confidence = v.confidence,
          evidence = v.evidence
        )
      }
    }

    /**
     * ✅ Java-API Compatible helper method for flatMap usage:
     *
     * Used from:
     *
     * candidates.flatMap(new RichFlatMapFunction[...] {
     *   override def flatMap(c, out) = LLMStage.scoreRelation(cfg, c, out)
     * })
     */
    def scoreRelation(
                       cfg: OllamaConfig,
                       candidate: RelationCandidate,
                       out: Collector[ScoredRelation]
                     ): Unit = {

      val client = OllamaClient(cfg)

      try {
        client.scoreRelation(candidate) match {
          case Some(v) =>
            out.collect(
              ScoredRelation(
                a = candidate.a,
                predicate = v.predicate,
                b = candidate.b,
                confidence = v.confidence,
                evidence = v.evidence
              )
            )

          case None =>
          // It's okay to not output anything
        }

      } catch {
        case e: Exception =>
          // Log but don't throw — keeps the pipeline alive
          println(s"[LLMStage] Error scoring relation: ${e.getMessage}")
      }
    }
  }
