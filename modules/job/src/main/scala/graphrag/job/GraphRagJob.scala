package graphrag.job

import graphrag.core.config.GraphRagConfig
import graphrag.core.model.GraphWrite
import graphrag.core.utils.Logging
import graphrag.neo4j.Neo4jSink

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.streaming.api.datastream.DataStream

/**
 * Main GraphRAG job that orchestrates the entire pipeline.
 */
class GraphRagJob(config: GraphRagConfig) extends Logging {

  def run(): Unit = {
    logInfo("Initializing Flink environment")

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(config.flink.parallelism)

    // Enable checkpointing for fault tolerance
    env.enableCheckpointing(config.flink.checkpointInterval)

    logInfo(s"Flink parallelism: ${config.flink.parallelism}")
    logInfo(s"Checkpoint interval: ${config.flink.checkpointInterval}ms")

    // Build the pipeline
    logInfo("Building GraphRAG pipeline")
    val graphWrites: DataStream[GraphWrite] = PipelineBuilder.buildPipeline(env, config)

    //Add Neo4j sink
    logInfo("Adding Neo4j sink")
    graphWrites.addSink(new Neo4jSink(config.neo4j))
      .name("neo4j-sink")

    // Execute the job
    logInfo("Starting pipeline execution")
    env.execute("GraphRAG Pipeline")

    logInfo("Pipeline execution completed")
  }
}