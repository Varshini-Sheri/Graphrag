package graphrag.job

import graphrag.core.config.GraphRagConfig
import graphrag.core.utils.Logging

object Main extends Logging {
  def main(args: Array[String]): Unit = {
    logInfo("=" * 60)
    logInfo("Starting GraphRAG Job")
    logInfo("=" * 60)

    try {
      val config = GraphRagConfig.load()
      logInfo(s"Config loaded:")
      logInfo(s"  Index source: ${config.index.chunks}")
      logInfo(s"  Neo4j: ${config.neo4j.uri}")
      logInfo(s"  Ollama: ${config.ollama.endpoint}")

      val job = new GraphRagJob(config)
      job.run()

      logInfo("=" * 60)
      logInfo("GraphRAG Job completed successfully!")
      logInfo("=" * 60)

    } catch {
      case e: Exception =>
        logError("GraphRAG Job failed", e)
        System.exit(1)
    }
  }
}