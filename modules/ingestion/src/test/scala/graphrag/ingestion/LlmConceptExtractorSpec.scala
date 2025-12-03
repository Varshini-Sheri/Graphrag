package graphrag.ingestion.concept

import graphrag.core.model.{Chunk, Concept, Mentions}
import graphrag.llm.OllamaClient
import graphrag.core.config.OllamaConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LlmConceptExtractorSpec extends AnyFlatSpec with Matchers {

  // Fake dummy client that never calls Ollama
  object FakeClient extends OllamaClient(
    OllamaConfig(
      endpoint = "http://fake",
      model = "none",
      temperature = 0.1,
      timeoutMs = 10,
      maxRetries = 0
    )
  ) {
    override def complete(prompt: String): Option[String] = None
  }

  "LlmConceptExtractor.extract" should "return empty list for very short chunk" in {
    val chunk = Chunk("c1", "doc1", (0, 10), "short text", "uri", "hash")

    val out = LlmConceptExtractor.extract(chunk, FakeClient)

    out shouldBe empty
  }
}
