package graphrag.llm

import graphrag.core.model.LlmVerdict
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LlmJsonParsingSpec extends AnyFlatSpec with Matchers {

  "OllamaClient.parseVerdict" should "extract a valid JSON verdict" in {
    val client = OllamaClient(
      config = graphrag.core.config.OllamaConfig(
        endpoint = "http://localhost",
        model = "test",
        temperature = 0.1,
        timeoutMs = 1000,
        maxRetries = 1
      )
    )

    val jsonResponse =
      """Some extra text before
        |{"predicate":"related_to","confidence":0.75,"evidence":"good","ref":"src"}
        |some extra text after""".stripMargin

    // call the private method via reflection to keep test simple
    val method = classOf[OllamaClient].getDeclaredMethod("parseVerdict", classOf[String])
    method.setAccessible(true)

    val result = method.invoke(client, jsonResponse).asInstanceOf[Option[LlmVerdict]]

    result.isDefined shouldBe true
    val v = result.get

    v.predicate shouldBe "related_to"
    v.confidence shouldBe 0.75
    v.evidence shouldBe "good"
    v.ref shouldBe "src"
  }
}
