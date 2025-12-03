package graphrag.neo4j

import graphrag.core.config.Neo4jConfig
import graphrag.core.model.GraphWrite
import graphrag.core.utils.Logging
import org.apache.flink.streaming.api.functions.sink.SinkFunction
import org.neo4j.driver.{AuthTokens, GraphDatabase, Session}

/**
 * Flink sink for writing graph data to Neo4j.
 * Uses idempotent MERGE operations for fault tolerance.
 */
class Neo4jSink(config: Neo4jConfig) extends SinkFunction[GraphWrite] with Logging {

  @transient private var driver: org.neo4j.driver.Driver = _
  @transient private var writeCount = 0L
  @transient private var relationCount = 0L
  @transient private var nodeCount = 0L

  override def invoke(value: GraphWrite, context: SinkFunction.Context): Unit = {
    if (driver == null) {
      logInfo(s"Initializing Neo4j driver: ${config.uri}")
      driver = GraphDatabase.driver(
        config.uri,
        AuthTokens.basic(config.user, config.password)
      )
      logInfo("Neo4j driver initialized successfully")
    }

    val session: Session = driver.session()
    try {
      // Convert to Cypher
      val statements = GraphUpsert.toCypher(value)

      // Track what we're writing
      value match {
        case node: graphrag.core.model.UpsertNode =>
          nodeCount += 1
          if (nodeCount % 100 == 0) {
            logInfo(s"Nodes written: $nodeCount")
          }
        case edge: graphrag.core.model.UpsertEdge =>
          relationCount += 1
          if (edge.rel == "RELATES_TO") {
            logInfo(s"★ Writing RELATES_TO: ${edge.fromId} -> ${edge.toId} (total RELATES_TO: $relationCount)")
          } else if (relationCount % 1000 == 0) {
            logInfo(s"Relationships written: $relationCount")
          }
      }

      // Execute statements
      statements.foreach { stmt =>
        session.run(stmt.query, stmt.toJavaParams)
        writeCount += 1
      }

      if (writeCount % 1000 == 0) {
        logInfo(s"Total writes to Neo4j: $writeCount (nodes: $nodeCount, edges: $relationCount)")
      }

    } catch {
      case e: Exception =>
        logError(s"Failed to write to Neo4j: ${e.getMessage}", e)
        logError(s"Failed GraphWrite: $value")
        throw e
    } finally {
      session.close()
    }
  }

  override def finish(): Unit = {
    logInfo("============================================================")
    logInfo("Neo4j Sink Statistics:")
    logInfo(s"  Total writes: $writeCount")
    logInfo(s"  Nodes created: $nodeCount")
    logInfo(s"  Relationships created: $relationCount")
    logInfo("============================================================")

    if (driver != null) {
      logInfo("Closing Neo4j driver")
      driver.close()
    }
  }
}

object Neo4jSink {
  def apply(config: Neo4jConfig): Neo4jSink = new Neo4jSink(config)
}