package graphrag.ingestion.relations

import graphrag.core.model._
import graphrag.core.utils.Logging
import org.apache.flink.api.common.functions.{FilterFunction, MapFunction}
import org.apache.flink.streaming.api.datastream.DataStream  // ✅ Java API

object RelationCandidateGenerator extends Logging {

  def generate(
                coOccurs: DataStream[CoOccur]
              ): DataStream[RelationCandidate] = {

    coOccurs
      .filter(new FilterFunction[CoOccur] {
        override def filter(c: CoOccur): Boolean = c.freq >= 1
      })
      .map(new MapFunction[CoOccur, RelationCandidate] {
        override def map(c: CoOccur): RelationCandidate =
          RelationCandidate(
            a = c.a,
            b = c.b,
            evidence = s"Co-occurred ${c.freq} times in window ${c.windowId}"
          )
      })
      .name("relation-candidates")
  }
}