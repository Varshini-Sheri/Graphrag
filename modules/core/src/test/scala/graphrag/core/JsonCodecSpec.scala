package graphrag.core

import graphrag.core.model.Chunk
import graphrag.core.serialization.JsonCodecs._
import io.circe.syntax._
import io.circe.parser._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JsonCodecSpec extends AnyFlatSpec with Matchers {
  "JsonCodecs" should "encode and decode Chunk" in {
    val chunk = Chunk("id1", "doc1", (0, 100), "text", "uri", "hash")
    val json = chunk.asJson.noSpaces
    val decoded = decode[Chunk](json)
    decoded.isRight shouldBe true
  }
}
