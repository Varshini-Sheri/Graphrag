package graphrag.llm

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PromptTemplateSpec extends AnyFlatSpec with Matchers {
  "LlmPromptTemplates" should "generate valid prompts" in {
    val prompt = LlmPromptTemplates.relationExtractionPrompt("A", "B", "evidence")
    prompt should include("Concept A")
  }
}
