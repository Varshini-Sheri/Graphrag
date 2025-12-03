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

  /**
   * Convert Neo4j Java values (Value, Map, List, primitive) into
   * clean Scala/JSON-safe values.
   */
  def anyToJsonValue(v: Any): Any = v match {
    case null => null

    case value: Value if !value.isNull =>
      value.`type`().name() match {
        case "MAP" =>
          val m = value.asMap().asInstanceOf[java.util.Map[String, Any]]
          val out = new java.util.HashMap[String, Any]()
          val iter = m.entrySet().iterator()
          while (iter.hasNext) {
            val e = iter.next()
            out.put(e.getKey, anyToJsonValue(e.getValue))
          }
          out

        case "LIST" =>
          val list = value.asList().asInstanceOf[java.util.List[Any]]
          val out = new java.util.ArrayList[Any]()
          val it = list.iterator()
          while (it.hasNext) {
            out.add(anyToJsonValue(it.next()))
          }
          out

        case _ =>
          Try(value.asObject()).getOrElse(value.toString)
      }

    case m: java.util.Map[_, _] =>
      val out = new java.util.HashMap[String, Any]()
      val it = m.entrySet().iterator()
      while (it.hasNext) {
        val e = it.next()
        out.put(e.getKey.toString, anyToJsonValue(e.getValue))
      }
      out

    case l: java.util.List[_] =>
      val out = new java.util.ArrayList[Any]()
      val it = l.iterator()
      while (it.hasNext) out.add(anyToJsonValue(it.next()))
      out

    case s: String => s
    case b: java.lang.Boolean => b
    case n: java.lang.Number => n

    case other => other.toString
  }

}
