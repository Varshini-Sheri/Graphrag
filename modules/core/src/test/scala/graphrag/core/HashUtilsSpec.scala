package graphrag.core

import graphrag.core.utils.HashUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HashUtilsSpec extends AnyFlatSpec with Matchers {
  "HashUtils.sha256" should "generate stable hashes" in {
    val hash1 = HashUtils.sha256("test")
    val hash2 = HashUtils.sha256("test")
    hash1 shouldEqual hash2
  }
}
