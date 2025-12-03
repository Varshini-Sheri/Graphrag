package graphrag.core.config

import com.typesafe.config.{Config, ConfigFactory}

case class GraphRagConfig(
                           ollama: OllamaConfig,
                           neo4j: Neo4jConfig,
                           index: IndexConfig,
                           flink: FlinkConfig,
                           s3: Option[S3Config] = None
                         )

case class OllamaConfig(
                         endpoint: String,
                         model: String,
                         temperature: Double,
                         timeoutMs: Int,
                         maxRetries: Int = 3
                       )

case class Neo4jConfig(
                        uri: String,
                        user: String,
                        password: String,
                        database: String = "neo4j",
                        maxConnectionPoolSize: Int = 10
                      )

case class IndexConfig(
                        documents: String,
                        chunks: String,
                        format: String = "jsonl",
                        batchSize: Int = 100
                      )


case class FlinkConfig(
                        parallelism: Int,
                        checkpointInterval: Long,
                        checkpointDir: String = "file:///tmp/flink-checkpoints"
                      )

case class S3Config(
                     region: String,
                     bucket: String,
                     accessKey: Option[String] = None,
                     secretKey: Option[String] = None
                   )

object GraphRagConfig {

  def load(): GraphRagConfig = fromConfig(ConfigFactory.load())

  def load(resourceName: String): GraphRagConfig =
    fromConfig(ConfigFactory.load(resourceName))

  def fromConfig(config: Config): GraphRagConfig = {
    GraphRagConfig(
      ollama = OllamaConfig(
        endpoint = config.getString("ollama.endpoint"),
        model = config.getString("ollama.model"),
        temperature = config.getDouble("ollama.temperature"),
        timeoutMs = config.getInt("ollama.timeoutMs"),
        maxRetries = getIntOrDefault(config, "ollama.maxRetries", 3)
      ),
      neo4j = Neo4jConfig(
        uri = config.getString("neo4j.uri"),
        user = config.getString("neo4j.user"),
        password = getEnvOrConfig(config, "NEO4J_PASSWORD", "neo4j.password"),
        database = getStringOrDefault(config, "neo4j.database", "neo4j"),
        maxConnectionPoolSize = getIntOrDefault(config, "neo4j.maxConnectionPoolSize", 10)
      ),
      index = IndexConfig(
        documents = config.getString("index.documents"),
        chunks    = config.getString("index.chunks"),
        format    = getStringOrDefault(config, "index.format", "jsonl"),
        batchSize = getIntOrDefault(config, "index.batchSize", 100)
      )
      ,
      flink = FlinkConfig(
        parallelism = config.getInt("flink.parallelism"),
        checkpointInterval = config.getLong("flink.checkpointInterval"),
        checkpointDir = getStringOrDefault(config, "flink.checkpointDir", "file:///tmp/flink-checkpoints")
      ),
      s3 = if (config.hasPath("s3")) Some(S3Config(
        region = config.getString("s3.region"),
        bucket = config.getString("s3.bucket"),
        accessKey = getOptionalString(config, "s3.accessKey"),
        secretKey = getOptionalString(config, "s3.secretKey")
      )) else None
    )
  }

  private def getEnvOrConfig(config: Config, envVar: String, configPath: String): String =
    sys.env.getOrElse(envVar, config.getString(configPath))

  private def getStringOrDefault(config: Config, path: String, default: String): String =
    if (config.hasPath(path)) config.getString(path) else default

  private def getIntOrDefault(config: Config, path: String, default: Int): Int =
    if (config.hasPath(path)) config.getInt(path) else default

  private def getOptionalString(config: Config, path: String): Option[String] =
    if (config.hasPath(path)) Some(config.getString(path)) else None

  /**
   * Create a minimal config for testing
   */
  def forTesting(): GraphRagConfig = GraphRagConfig(
    ollama = OllamaConfig(
      endpoint = "http://localhost:11434",
      model = "llama3:instruct",
      temperature = 0.0,
      timeoutMs = 120000
    ),
    neo4j = Neo4jConfig(
      uri = "bolt://localhost:7687",
      user = "neo4j",
      password = "password"
    ),
    index = IndexConfig(
      documents = "data/test-documents.jsonl",
      chunks    = "data/test-chunks.jsonl",
      format = "jsonl"
    )
    ,
    flink = FlinkConfig(
      parallelism = 1,
      checkpointInterval = 10000L
    )
  )
}