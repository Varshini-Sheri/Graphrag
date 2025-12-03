import sbt._
import sbt.Keys._
import sbtassembly.AssemblyPlugin.autoImport._

name := "graphrag"
version := "0.1.0"
scalaVersion := "2.12.18"

val flinkVersion        = "1.20.3"
val neo4jDriverVersion  = "5.14.0"
val circeVersion        = "0.14.6"
val scalaTestVersion    = "3.2.17"
val sttpVersion         = "3.9.1"

/* ------------------------------------------------------------------
   Common settings shared across modules
   ------------------------------------------------------------------ */
lazy val commonSettings = Seq(
  scalaVersion := "2.12.18",
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-language:postfixOps",
    "-language:higherKinds"
  )
)

/* ------------------------------------------------------------------
   Dependencies shared across modules
   ------------------------------------------------------------------ */
lazy val commonDependencies = Seq(
  "ch.qos.logback" % "logback-classic" % "1.4.11",
  "com.typesafe"   % "config"          % "1.4.3",

  // unit tests
  "org.scalatest" %% "scalatest"       % scalaTestVersion % Test,

  // AWS S3
  "software.amazon.awssdk" % "s3"      % "2.25.48",
  "software.amazon.awssdk" % "regions" % "2.25.48"
)

/* ------------------------------------------------------------------
   Flink dependencies
   ------------------------------------------------------------------ */
lazy val flinkScalaDeps = Seq(
  "org.apache.flink" %% "flink-scala"           % flinkVersion,
  "org.apache.flink" %% "flink-streaming-scala" % flinkVersion
)

/* ------------------------------------------------------------------
   Root aggregate project (NO assembly)
   ------------------------------------------------------------------ */
lazy val root = (project in file("."))
  .aggregate(core, ingestion, llm, neo4j, api, job)
  .settings(
    commonSettings,
    publish / skip := true,
    assembly / skip := true
  )

/* ------------------------------------------------------------------
   CORE MODULE
   ------------------------------------------------------------------ */
lazy val core = (project in file("modules/core"))
  .settings(
    name := "graphrag-core",
    commonSettings,
    assembly / skip := true,
    libraryDependencies ++= commonDependencies ++ Seq(
      "io.circe" %% "circe-core"    % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser"  % circeVersion
    )
  )

/* ------------------------------------------------------------------
   INGESTION MODULE (Flink)
   ------------------------------------------------------------------ */
lazy val ingestion = (project in file("modules/ingestion"))
  .dependsOn(core, llm)
  .settings(
    name := "graphrag-ingestion",
    commonSettings,
    assembly / skip := true,
    libraryDependencies ++= commonDependencies ++ flinkScalaDeps
  )

/* ------------------------------------------------------------------
   LLM MODULE
   ------------------------------------------------------------------ */
lazy val llm = (project in file("modules/llm"))
  .dependsOn(core)
  .settings(
    name := "graphrag-llm",
    commonSettings,
    assembly / skip := true,
    libraryDependencies ++= commonDependencies ++ Seq(
      "com.softwaremill.sttp.client3" %% "core"  % sttpVersion,
      "com.softwaremill.sttp.client3" %% "circe" % sttpVersion
    ) ++ flinkScalaDeps
  )

/* ------------------------------------------------------------------
   NEO4J MODULE
   ------------------------------------------------------------------ */
lazy val neo4j = (project in file("modules/neo4j"))
  .dependsOn(core)
  .settings(
    name := "graphrag-neo4j",
    commonSettings,
    assembly / skip := true,
    libraryDependencies ++= commonDependencies ++ Seq(
      "org.neo4j.driver" % "neo4j-java-driver" % neo4jDriverVersion
    ) ++ flinkScalaDeps
  )

/* ------------------------------------------------------------------
   API MODULE
   ------------------------------------------------------------------ */
lazy val api = (project in file("modules/api"))
  .settings(
    name := "graphrag-api",
    libraryDependencies ++= Seq(
      // Akka HTTP
      "com.typesafe.akka" %% "akka-actor" % "2.6.20",
      "com.typesafe.akka" %% "akka-stream" % "2.6.20",
      "com.typesafe.akka" %% "akka-http" % "10.2.10",

      // JSON support
      "de.heikoseeberger" %% "akka-http-circe" % "1.39.2",
      "io.circe" %% "circe-core" % "0.14.6",
      "io.circe" %% "circe-generic" % "0.14.6",
      "io.circe" %% "circe-parser" % "0.14.6",

      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.11"
    ),
    assembly / assemblyJarName := "graphrag-api-assembly-0.1.0-SNAPSHOT.jar",
    assembly / mainClass := Some("graphrag.api.HttpServer"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) =>
        xs match {
          case "MANIFEST.MF" :: Nil => MergeStrategy.discard
          case "services" :: _      => MergeStrategy.concat
          case _                    => MergeStrategy.discard
        }
      case "reference.conf" => MergeStrategy.concat
      case "application.conf" => MergeStrategy.concat
      case x if x.endsWith(".proto") => MergeStrategy.first
      case PathList("google", "protobuf", xs @ _*) => MergeStrategy.first
      case _ => MergeStrategy.first
    }
  )
  .dependsOn(core, neo4j, llm)
/* ------------------------------------------------------------------
   JOB MODULE — THE ONLY MODULE THAT PRODUCES A FAT JAR
   ------------------------------------------------------------------ */
lazy val job = (project in file("modules/job"))
  .dependsOn(core, ingestion, llm, neo4j)
  .settings(
    name := "graphrag-job",
    commonSettings,
    assembly / skip := false,
    libraryDependencies ++= commonDependencies ++ flinkScalaDeps ++ Seq(
      "org.apache.flink" % "flink-clients" % flinkVersion    // ✅ Single % not %%
    ),

    assembly / mainClass := Some("graphrag.job.Main"),

    // --- COMPREHENSIVE MERGE STRATEGY ---
    assembly / assemblyMergeStrategy := {
      case PathList("module-info.class") => MergeStrategy.discard
      case x if x.endsWith("/module-info.class") => MergeStrategy.discard
      case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
      case PathList("META-INF", "native-image", xs @ _*) => MergeStrategy.first
      case PathList("META-INF", "versions", xs @ _*) => MergeStrategy.first
      case PathList("META-INF", xs @ _*) => xs match {
        case "MANIFEST.MF" :: Nil => MergeStrategy.discard
        case "services" :: _ => MergeStrategy.concat  // ✅ Keep services for SPI
        case _ => MergeStrategy.discard
      }
      case "reference.conf" => MergeStrategy.concat
      case "application.conf" => MergeStrategy.concat
      case _ => MergeStrategy.first
    },

    assembly / test := {}
  )