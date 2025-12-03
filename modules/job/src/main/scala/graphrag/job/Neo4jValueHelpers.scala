package graphrag.api

import org.neo4j.driver.Value
import org.neo4j.driver.types.Node
import scala.util.Try

/**
 * Helper object for safely extracting values from Neo4j driver results
 */
object Neo4jValueHelpers {

  /**
   * Safely extract a Node from an Object returned by Neo4j
   */
  def extractNode(obj: Any): Option[Node] = {
    Try {
      obj match {
        case v: Value if !v.isNull => v.asNode()
        case n: Node => n
        case _ => null
      }
    }.toOption.filter(_ != null)
  }

  /**
   * Safely extract a String from a Node property
   */
  def getNodeString(node: Node, key: String, default: String = ""): String = {
    Try {
      if (node.containsKey(key)) {
        val value = node.get(key)
        if (!value.isNull) value.asString() else default
      } else default
    }.getOrElse(default)
  }

  /**
   * Safely extract an Int from a Node property
   */
  def getNodeInt(node: Node, key: String, default: Int = 0): Int = {
    Try {
      if (node.containsKey(key)) {
        val value = node.get(key)
        if (!value.isNull) value.asInt() else default
      } else default
    }.getOrElse(default)
  }

  /**
   * Safely extract a Long from a Node property
   */
  def getNodeLong(node: Node, key: String, default: Long = 0L): Long = {
    Try {
      if (node.containsKey(key)) {
        val value = node.get(key)
        if (!value.isNull) value.asLong() else default
      } else default
    }.getOrElse(default)
  }

  /**
   * Safely extract a Double from a Node property
   */
  def getNodeDouble(node: Node, key: String, default: Double = 0.0): Double = {
    Try {
      if (node.containsKey(key)) {
        val value = node.get(key)
        if (!value.isNull) value.asDouble() else default
      } else default
    }.getOrElse(default)
  }

  /**
   * Check if a Value is null or the object itself is null
   */
  def isNullValue(obj: Any): Boolean = {
    obj == null || (obj match {
      case v: Value => v.isNull
      case _ => false
    })
  }

  /**
   * Safely convert Object to Value
   */
  def toValue(obj: Any): Option[Value] = {
    Try {
      obj match {
        case v: Value => v
        case _ => null
      }
    }.toOption.filter(_ != null)
  }
}