package graphrag.job

import graphrag.core.model._
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.scala._

/**
 * Explicit TypeInformation for all domain types.
 * Must be in job module where Flink dependencies exist.
 */
object TypeInfo {

  implicit val chunkTypeInfo: TypeInformation[Chunk] =
    createTypeInformation[Chunk]

  implicit val conceptTypeInfo: TypeInformation[Concept] =
    createTypeInformation[Concept]

  implicit val mentionsTypeInfo: TypeInformation[Mentions] =
    createTypeInformation[Mentions]

  implicit val coOccurTypeInfo: TypeInformation[CoOccur] =
    createTypeInformation[CoOccur]

  implicit val scoredRelationTypeInfo: TypeInformation[ScoredRelation] =
    createTypeInformation[ScoredRelation]

  implicit val graphWriteTypeInfo: TypeInformation[GraphWrite] =
    createTypeInformation[GraphWrite]
}