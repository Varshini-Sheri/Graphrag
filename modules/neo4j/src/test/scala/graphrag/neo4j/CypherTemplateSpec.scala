package graphrag.neo4j

import graphrag.neo4j.CypherTemplates
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CypherTemplatesSpec extends AnyFlatSpec with Matchers {
  "CypherTemplates.upsertNode" should "include MERGE and SET" in {
    val query = CypherTemplates.upsertNode("Concept")
    query should include ("MERGE")
    query should include ("Concept")
    query should include ("SET")
  }
}
