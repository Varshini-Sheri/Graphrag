package graphrag.core.serialization

import graphrag.core.model._
import io.circe.{Encoder, Decoder, Json}
import io.circe.syntax._
import io.circe.parser._

/**
 * Helper trait for JSON serialization support.
 * Mix this into classes that need JSON capabilities.
 */
trait CirceSupport {
  import JsonCodecs._

  def toJson[A: Encoder](a: A): Json = a.asJson

  def toJsonString[A: Encoder](a: A): String = a.asJson.noSpaces

  def toJsonPretty[A: Encoder](a: A): String = a.asJson.spaces2

  def fromJson[A: Decoder](json: Json): Either[io.circe.Error, A] = json.as[A]

  def fromJsonString[A: Decoder](jsonStr: String): Either[io.circe.Error, A] =
    parse(jsonStr).flatMap(_.as[A])

  def parseJson(jsonStr: String): Either[io.circe.ParsingFailure, Json] =
    parse(jsonStr)
}

object CirceSupport extends CirceSupport {

  /**
   * Serialize a Chunk to JSON string
   */
  def chunkToJson(chunk: Chunk): String = {
    import JsonCodecs._
    chunk.asJson.noSpaces
  }

  /**
   * Deserialize a Chunk from JSON string
   */
  def jsonToChunk(json: String): Either[io.circe.Error, Chunk] = {
    import JsonCodecs._
    parse(json).flatMap(_.as[Chunk])
  }

  /**
   * Serialize a Concept to JSON string
   */
  def conceptToJson(concept: Concept): String = {
    import JsonCodecs._
    concept.asJson.noSpaces
  }

  /**
   * Deserialize a Concept from JSON string
   */
  def jsonToConcept(json: String): Either[io.circe.Error, Concept] = {
    import JsonCodecs._
    parse(json).flatMap(_.as[Concept])
  }

  /**
   * Serialize a ScoredRelation to JSON string
   */
  def scoredRelationToJson(rel: ScoredRelation): String = {
    import JsonCodecs._
    rel.asJson.noSpaces
  }
}