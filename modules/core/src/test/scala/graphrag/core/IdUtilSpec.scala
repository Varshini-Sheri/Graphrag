package graphrag.core

import graphrag.core.utils.IdUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IdUtilSpec extends AnyFlatSpec with Matchers {
  "IdUtils.uuid" should "generate unique IDs" in {
    val id1 = IdUtils.uuid()
    val id2 = IdUtils.uuid()
    id1 should not equal id2
  }
}
