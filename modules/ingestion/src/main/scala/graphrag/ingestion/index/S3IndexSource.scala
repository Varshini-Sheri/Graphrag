package graphrag.ingestion.index

import graphrag.core.model.Chunk
import graphrag.core.config.S3Config
import graphrag.core.utils.Logging
import io.circe.parser._
import io.circe.generic.auto._

import org.apache.flink.streaming.api.functions.source.SourceFunction
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  ListObjectsV2Request
}

import scala.collection.JavaConverters._
import java.io.{BufferedReader, InputStreamReader}
import java.util.zip.GZIPInputStream

/**
 * Reads .jsonl or .jsonl.gz from S3 paths.
 */
class S3IndexSource(s3Path: String, cfg: Option[S3Config] = None)
  extends SourceFunction[Chunk] with Logging {

  @volatile private var isRunning = true

  private lazy val s3: S3Client = {
    val builder = S3Client.builder()
    cfg.foreach(c => builder.region(Region.of(c.region)))
    builder.build()
  }

  override def run(ctx: SourceFunction.SourceContext[Chunk]): Unit = {
    val (bucket, prefix) = parse(s3Path)

    if (prefix.endsWith("/"))
      processPrefix(bucket, prefix, ctx)
    else
      processObject(bucket, prefix, ctx)
  }

  private def parse(path: String): (String, String) = {
    val Pattern = """s3[a]?://([^/]+)/(.+)""".r
    path match {
      case Pattern(b, k) => (b, k)
      case _ => throw new IllegalArgumentException(s"Invalid S3 path: $path")
    }
  }

  private def processPrefix(bucket: String, prefix: String, ctx: SourceFunction.SourceContext[Chunk]): Unit = {
    val req = ListObjectsV2Request.builder()
      .bucket(bucket)
      .prefix(prefix)
      .build()

    val objects = s3.listObjectsV2(req).contents().asScala
      .filter(o => o.key().endsWith(".jsonl") || o.key().endsWith(".jsonl.gz"))
      .sortBy(_.key())

    objects.foreach(obj => if (isRunning) processObject(bucket, obj.key(), ctx))
  }

  private def processObject(bucket: String, key: String, ctx: SourceFunction.SourceContext[Chunk]): Unit = {
    val req = GetObjectRequest.builder().bucket(bucket).key(key).build()
    val stream = s3.getObject(req)

    val input =
      if (key.endsWith(".gz")) new GZIPInputStream(stream)
      else stream

    val reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))

    try {
      var line = reader.readLine()

      while (line != null && isRunning) {
        val clean = line.stripPrefix("\uFEFF").trim
        if (clean.nonEmpty) {
          decode[ChunkJson](clean) match {
            case Right(cj) =>
              ctx.collect(
                Chunk(
                  cj.chunkId,
                  cj.docId,
                  cj.span,
                  cj.text,
                  cj.sourceUri,
                  cj.hash
                )
              )
            case Left(err) =>
              logWarn(s"Failed to decode JSON: ${err.getMessage}")
          }
        }

        line = reader.readLine()
      }
    } finally {
      reader.close()
    }
  }

  override def cancel(): Unit = isRunning = false
}

object S3IndexSource {
  def apply(path: String): S3IndexSource = new S3IndexSource(path)
  def apply(path: String, cfg: S3Config): S3IndexSource = new S3IndexSource(path, Some(cfg))
}
