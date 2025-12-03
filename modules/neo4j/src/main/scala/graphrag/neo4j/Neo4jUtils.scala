package graphrag.neo4j

import graphrag.core.config.Neo4jConfig
import graphrag.core.utils.Logging
import org.neo4j.driver.{AuthTokens, Driver, GraphDatabase, Result, Session, Transaction}
import scala.util.{Try, Success, Failure}

/**
 * Neo4j utility functions for session/transaction management
 */
object Neo4jUtils extends Logging {

  /**
   * Create a Neo4j driver from config
   */
  def createDriver(config: Neo4jConfig): Driver = {
    logInfo(s"Connecting to Neo4j at ${config.uri}")
    GraphDatabase.driver(
      config.uri,
      AuthTokens.basic(config.user, config.password)
    )
  }

  /**
   * Loan pattern for session management
   */
  def withSession[T](driver: Driver)(f: Session => T): T = {
    val session = driver.session()
    try f(session)
    finally session.close()
  }

  /**
   * Loan pattern for transaction management with auto-commit
   */
  def withTransaction[T](driver: Driver)(f: Transaction => T): T = {
    withSession(driver) { session =>
      val tx = session.beginTransaction()
      try {
        val result = f(tx)
        tx.commit()
        result
      } catch {
        case e: Exception =>
          tx.rollback()
          throw e
      } finally {
        tx.close()
      }
    }
  }

  /**
   * Execute a single CypherStatement
   */
  def execute(session: Session, stmt: CypherStatement): Result = {
    session.run(stmt.query, stmt.toJavaParams)
  }

  /**
   * Execute a single CypherStatement within a transaction
   */
  def execute(tx: Transaction, stmt: CypherStatement): Result = {
    tx.run(stmt.query, stmt.toJavaParams)
  }

  /**
   * Execute multiple statements in a single transaction
   */
  def executeBatch(driver: Driver, statements: Seq[CypherStatement]): Try[Int] = {
    Try {
      withTransaction(driver) { tx =>
        statements.foreach { stmt =>
          tx.run(stmt.query, stmt.toJavaParams)
        }
        statements.size
      }
    }
  }

  /**
   * Execute a GraphWrite operation
   */
  def executeWrite(driver: Driver, write: graphrag.core.model.GraphWrite): Try[Unit] = {
    Try {
      val statements = GraphUpsert.toCypher(write)
      withSession(driver) { session =>
        statements.foreach { stmt =>
          execute(session, stmt)
        }
      }
    }
  }

  /**
   * Execute multiple GraphWrite operations in a batch
   */
  def executeWriteBatch(driver: Driver, writes: Seq[graphrag.core.model.GraphWrite]): Try[Int] = {
    val statements = GraphUpsert.toCypherBatch(writes)
    executeBatch(driver, statements)
  }

  /**
   * Initialize database with indexes
   */
  def initializeIndexes(driver: Driver): Try[Unit] = {
    Try {
      withSession(driver) { session =>
        CypherTemplates.createIndexes.foreach { indexQuery =>
          try {
            session.run(indexQuery)
            logInfo(s"Created index: ${indexQuery.take(60)}...")
          } catch {
            case e: Exception =>
              logWarn(s"Index may already exist: ${e.getMessage}")
          }
        }
      }
    }
  }

  /**
   * Get graph statistics
   */
  def getStats(driver: Driver): Map[String, Long] = {
    withSession(driver) { session =>
      val nodeResult = session.run(CypherTemplates.countNodes)
      val relResult = session.run(CypherTemplates.countRelationships)

      import scala.collection.JavaConverters._

      val nodeCounts = nodeResult.list().asScala.map { record =>
        val label = record.get("label").asString()
        val count = record.get("count").asLong()
        s"nodes:$label" -> count
      }.toMap

      val relCounts = relResult.list().asScala.map { record =>
        val relType = record.get("type").asString()
        val count = record.get("count").asLong()
        s"rels:$relType" -> count
      }.toMap

      nodeCounts ++ relCounts
    }
  }

  /**
   * Test connection to Neo4j
   */
  def testConnection(driver: Driver): Boolean = {
    Try {
      withSession(driver) { session =>
        session.run("RETURN 1 AS test").single().get("test").asInt() == 1
      }
    } match {
      case Success(result) =>
        logInfo("Neo4j connection test: SUCCESS")
        result
      case Failure(e) =>
        logError(s"Neo4j connection test: FAILED - ${e.getMessage}")
        false
    }
  }

  /**
   * Clear all data (use with caution!)
   */
  def clearDatabase(driver: Driver): Try[Unit] = {
    logWarn("Clearing entire Neo4j database!")
    Try {
      withSession(driver) { session =>
        session.run("MATCH (n) DETACH DELETE n")
      }
    }
  }
}