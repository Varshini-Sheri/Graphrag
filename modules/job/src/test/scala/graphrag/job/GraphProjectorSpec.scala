package graphrag.job

import graphrag.core.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GraphProjectorSpec extends AnyFlatSpec with Matchers {

  "GraphWrite.chunkNode" should "produce a valid Chunk UpsertNode" in {
    val out = GraphWrite.chunkNode(
      chunkId = "c1",
      docId = "d1",
      text = "hello",
      spanStart = 0,
      spanEnd = 10,
      sourceUri = "uri",
      hash = "h"
    )

    out shouldBe a [UpsertNode]
    val n = out.asInstanceOf[UpsertNode]

    n.label shouldBe "Chunk"
    n.id shouldBe "c1"
    n.props("docId") shouldBe "d1"
  }

  "GraphWrite.conceptNode" should "produce a Concept UpsertNode" in {
    val out = GraphWrite.conceptNode(
      conceptId = "cid",
      lemma = "run",
      surface = "running",
      origin = "origin",
      name = "concept-name"
    )

    out shouldBe a [UpsertNode]
    val n = out.asInstanceOf[UpsertNode]

    n.label shouldBe "Concept"
    n.id shouldBe "cid"
    n.props("lemma") shouldBe "run"
  }


  "ScoredRelation.toEdge" should "produce a RELATES_TO UpsertEdge" in {
    val a = Concept("A", "lemmaA", "A", "originA", "nameA")
    val b = Concept("B", "lemmaB", "B", "originB", "nameB")

    val r = ScoredRelation(
      a = a,
      b = b,
      predicate = "rel",
      confidence = 0.9,
      evidence = "text"
    )

    val e = r.toEdge.asInstanceOf[UpsertEdge]

    e.rel shouldBe "RELATES_TO"
    e.fromId shouldBe "A"
    e.toId shouldBe "B"
    e.props("predicate") shouldBe "rel"
  }
}
