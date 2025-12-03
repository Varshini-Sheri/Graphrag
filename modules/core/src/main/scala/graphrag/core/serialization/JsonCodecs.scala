package graphrag.core.serialization

import graphrag.core.model._
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.generic.auto._

/**
 * JSON codecs for all model types using Circe.
 */
object JsonCodecs {

  // Tuple2 codec for span
  implicit def tuple2Encoder[A: Encoder, B: Encoder]: Encoder[(A, B)] =
    Encoder.instance { case (a, b) => Json.arr(a.asJson, b.asJson) }

  implicit def tuple2Decoder[A: Decoder, B: Decoder]: Decoder[(A, B)] =
    Decoder.instance { cursor =>
      for {
        a <- cursor.downN(0).as[A]
        b <- cursor.downN(1).as[B]
      } yield (a, b)
    }

  // Chunk codecs
  implicit val chunkEncoder: Encoder[Chunk] = deriveEncoder[Chunk]
  implicit val chunkDecoder: Decoder[Chunk] = deriveDecoder[Chunk]

  // Concept codecs
  implicit val conceptEncoder: Encoder[Concept] = deriveEncoder[Concept]
  implicit val conceptDecoder: Decoder[Concept] = deriveDecoder[Concept]

  // Mentions codecs
  implicit val mentionsEncoder: Encoder[Mentions] = deriveEncoder[Mentions]
  implicit val mentionsDecoder: Decoder[Mentions] = deriveDecoder[Mentions]

  // CoOccur codecs
  implicit val coOccurEncoder: Encoder[CoOccur] = deriveEncoder[CoOccur]
  implicit val coOccurDecoder: Decoder[CoOccur] = deriveDecoder[CoOccur]

  // RelationCandidate codecs
  implicit val relationCandidateEncoder: Encoder[RelationCandidate] = deriveEncoder[RelationCandidate]
  implicit val relationCandidateDecoder: Decoder[RelationCandidate] = deriveDecoder[RelationCandidate]

  // ScoredRelation codecs
  implicit val scoredRelationEncoder: Encoder[ScoredRelation] = deriveEncoder[ScoredRelation]
  implicit val scoredRelationDecoder: Decoder[ScoredRelation] = deriveDecoder[ScoredRelation]

  // LlmVerdict codecs
  implicit val llmVerdictEncoder: Encoder[LlmVerdict] = deriveEncoder[LlmVerdict]
  implicit val llmVerdictDecoder: Decoder[LlmVerdict] = deriveDecoder[LlmVerdict]

  // GraphWrite codecs (sealed trait)
  implicit val upsertNodeEncoder: Encoder[UpsertNode] = deriveEncoder[UpsertNode]
  implicit val upsertEdgeEncoder: Encoder[UpsertEdge] = deriveEncoder[UpsertEdge]

  implicit val graphWriteEncoder: Encoder[GraphWrite] = Encoder.instance {
    case node: UpsertNode => Json.obj(
      "type" -> "UpsertNode".asJson,
      "data" -> node.asJson
    )
    case edge: UpsertEdge => Json.obj(
      "type" -> "UpsertEdge".asJson,
      "data" -> edge.asJson
    )
  }

  // Any encoder for props (best effort)
  implicit val anyEncoder: Encoder[Any] = Encoder.instance {
    case s: String => s.asJson
    case i: Int => i.asJson
    case l: Long => l.asJson
    case d: Double => d.asJson
    case b: Boolean => b.asJson
    case null => Json.Null
    case seq: Seq[_] => Json.arr(seq.map(v => anyEncoder(v)): _*)
    case map: Map[_, _] =>
      Json.obj(map.map { case (k, v) => k.toString -> anyEncoder(v) }.toSeq: _*)
    case other => other.toString.asJson
  }

  implicit val anyDecoder: Decoder[Any] = Decoder.instance { cursor =>
    cursor.focus match {
      case Some(json) =>
        json.fold(
          jsonNull = Right(null),
          jsonBoolean = b => Right(b),
          jsonNumber = n => Right(n.toDouble),
          jsonString = s => Right(s),
          jsonArray = arr => Right(arr.flatMap(_.as[Any].toOption)),
          jsonObject = obj => Right(obj.toMap.mapValues(_.as[Any].getOrElse(null)))
        )
      case None => Left(DecodingFailure("No value", cursor.history))
    }
  }
}