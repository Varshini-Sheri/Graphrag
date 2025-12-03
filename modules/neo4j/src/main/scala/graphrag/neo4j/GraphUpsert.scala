package graphrag.neo4j

import graphrag.core.model.{GraphWrite, UpsertEdge, UpsertNode}
import graphrag.core.utils.Logging

/**
 * Converts GraphWrite ADT to executable CypherStatements.
 * Uses consistent ID property naming across all node types.
 */
object GraphUpsert extends Logging {

  def toCypher(write: GraphWrite): Seq[CypherStatement] = write match {

    // ----------------------------------------------------------------------
    // NODE UPSERTS
    // ----------------------------------------------------------------------

    case UpsertNode(label, id, props) =>
      logDebug(s"Converting UpsertNode to Cypher: label=$label, id=$id")

      label match {
        case "Chunk" =>
          logDebug(s"Creating Chunk node: $id with docId=${props.getOrElse("docId", "")}")
          Seq(CypherStatement(
            CypherTemplates.upsertChunk,
            Map(
              "id"        -> id,
              "docId"     -> props.getOrElse("docId", ""),
              "text"      -> props.getOrElse("text", ""),
              "spanStart" -> props.getOrElse("spanStart", 0),
              "spanEnd"   -> props.getOrElse("spanEnd", 0),
              "sourceUri" -> props.getOrElse("sourceUri", ""),
              "hash"      -> props.getOrElse("hash", "")
            )
          ))

        case "Concept" =>
          logDebug(s"Creating Concept node: $id (${props.getOrElse("lemma", "")})")
          Seq(CypherStatement(
            CypherTemplates.upsertConcept,
            Map(
              "id"      -> id,
              "lemma"   -> props.getOrElse("lemma", ""),
              "surface" -> props.getOrElse("surface", ""),
              "origin"  -> props.getOrElse("origin", ""),
              "name"    -> props.getOrElse("name", "")  // ADD THIS LINE
            )
          ))

        case "Paper" =>
          logDebug(s"Creating Paper node: $id with props: $props")
          // Use 'id' as the unique identifier property for Paper nodes
          Seq(CypherStatement(
            "MERGE (p:Paper {id: $id}) SET p += $props",
            Map(
              "id"    -> id,
              "props" -> props
            )
          ))

        case "Task" =>
          logDebug(s"Creating Task node: $id")
          Seq(CypherStatement(
            "MERGE (t:Task {id: $id}) SET t += $props",
            Map("id" -> id, "props" -> props)
          ))

        case "Dataset" =>
          logDebug(s"Creating Dataset node: $id")
          Seq(CypherStatement(
            "MERGE (d:Dataset {id: $id}) SET d += $props",
            Map("id" -> id, "props" -> props)
          ))

        case "Technique" =>
          logDebug(s"Creating Technique node: $id")
          Seq(CypherStatement(
            "MERGE (t:Technique {id: $id}) SET t += $props",
            Map("id" -> id, "props" -> props)
          ))

        case "Metric" =>
          logDebug(s"Creating Metric node: $id")
          Seq(CypherStatement(
            "MERGE (m:Metric {id: $id}) SET m += $props",
            Map("id" -> id, "props" -> props)
          ))

        case "Baseline" =>
          logDebug(s"Creating Baseline node: $id")
          Seq(CypherStatement(
            "MERGE (b:Baseline {id: $id}) SET b += $props",
            Map("id" -> id, "props" -> props)
          ))

        case "Snippet" =>
          logDebug(s"Creating Snippet node: $id")
          Seq(CypherStatement(
            "MERGE (s:Snippet {id: $id}) SET s += $props",
            Map("id" -> id, "props" -> props)
          ))

        case _ =>
          logWarn(s"Creating generic node with label: $label, id: $id")
          Seq(CypherStatement(
            CypherTemplates.upsertNode(label),
            Map("id" -> id, "props" -> props)
          ))
      }

    // ----------------------------------------------------------------------
    // EDGE UPSERTS
    // ----------------------------------------------------------------------

    case UpsertEdge(fromLabel, fromId, rel, toLabel, toId, props) =>
      logDebug(s"Converting UpsertEdge to Cypher: $fromLabel($fromId) -[$rel]-> $toLabel($toId)")

      (fromLabel, rel, toLabel) match {

        case ("Chunk", "MENTIONS", "Concept") =>
          logDebug(s"Creating MENTIONS relationship: chunk $fromId -> concept $toId")
          Seq(CypherStatement(
            CypherTemplates.upsertMentions,
            Map(
              "chunkId"   -> fromId,
              "conceptId" -> toId,
              "surface"   -> props.getOrElse("surface", "")
            )
          ))

        case ("Concept", "CO_OCCURS", "Concept") =>
          logDebug(s"Creating CO_OCCURS relationship: $fromId <-> $toId (freq=${props.getOrElse("freq", 1L)})")
          Seq(CypherStatement(
            CypherTemplates.upsertCoOccurs,
            Map(
              "aId"      -> fromId,
              "bId"      -> toId,
              "freq"     -> props.getOrElse("freq", 1L),
              "windowId" -> props.getOrElse("windowId", "")
            )
          ))

        case ("Concept", "RELATES_TO", "Concept") =>
          val confidence = props.getOrElse("confidence", 0.0)
          val predicate  = props.getOrElse("predicate", "related_to")
          logInfo(s"✓ Creating RELATES_TO relationship: $fromId -> $toId (predicate=$predicate, confidence=$confidence)")
          Seq(CypherStatement(
            CypherTemplates.upsertRelatesTo,
            Map(
              "aId"       -> fromId,
              "bId"       -> toId,
              "predicate" -> predicate,
              "confidence"-> confidence,
              "evidence"  -> props.getOrElse("evidence", "")
            )
          ))

        case ("Chunk", "BELONGS_TO", "Paper") =>
          logDebug(s"Creating BELONGS_TO relationship: chunk $fromId -> paper $toId")
          Seq(CypherStatement(
            """MERGE (ch:Chunk {id: $chunkId})
               MERGE (p:Paper {id: $paperId})
               MERGE (ch)-[:BELONGS_TO]->(p)""".stripMargin,
            Map("chunkId" -> fromId, "paperId" -> toId)
          ))

        case ("Paper", "ADDRESSES", "Task") =>
          logDebug(s"Creating ADDRESSES relationship: paper $fromId -> task $toId")
          Seq(CypherStatement(
            """MERGE (p:Paper {id: $paperId})
               MERGE (t:Task {id: $taskId})
               MERGE (p)-[:ADDRESSES]->(t)""".stripMargin,
            Map("paperId" -> fromId, "taskId" -> toId)
          ))

        case ("Paper", "USES_DATASET", "Dataset") =>
          logDebug(s"Creating USES_DATASET relationship: paper $fromId -> dataset $toId")
          Seq(CypherStatement(
            """MERGE (p:Paper {id: $paperId})
               MERGE (d:Dataset {id: $dsId})
               MERGE (p)-[:USES_DATASET]->(d)""".stripMargin,
            Map("paperId" -> fromId, "dsId" -> toId)
          ))

        case ("Paper", "PROPOSES", "Technique") =>
          logDebug(s"Creating PROPOSES relationship: paper $fromId -> technique $toId")
          Seq(CypherStatement(
            """MERGE (p:Paper {id: $paperId})
               MERGE (t:Technique {id: $techId})
               MERGE (p)-[:PROPOSES]->(t)""".stripMargin,
            Map("paperId" -> fromId, "techId" -> toId)
          ))

        case ("Paper", "REPORTS", "Metric") =>
          logDebug(s"Creating REPORTS relationship: paper $fromId -> metric $toId")
          Seq(CypherStatement(
            """MERGE (p:Paper {id: $paperId})
               MERGE (m:Metric {id: $metricId})
               MERGE (p)-[r:REPORTS]->(m) SET r += $props""".stripMargin,
            Map("paperId" -> fromId, "metricId" -> toId, "props" -> props)
          ))

        case ("Paper", "IMPROVES_OVER", "Baseline") =>
          logDebug(s"Creating IMPROVES_OVER relationship: paper $fromId -> baseline $toId")
          Seq(CypherStatement(
            """MERGE (p:Paper {id: $paperId})
               MERGE (b:Baseline {id: $baseId})
               MERGE (p)-[r:IMPROVES_OVER]->(b) SET r += $props""".stripMargin,
            Map("paperId" -> fromId, "baseId" -> toId, "props" -> props)
          ))

        case ("Concept", "EVIDENCE", "Snippet") =>
          logDebug(s"Creating EVIDENCE relationship: concept $fromId -> snippet $toId")
          Seq(CypherStatement(
            """MERGE (c:Concept {conceptId: $cid})
               MERGE (s:Snippet {id: $sid})
               MERGE (c)-[r:EVIDENCE]->(s) SET r += $props""".stripMargin,
            Map("cid" -> fromId, "sid" -> toId, "props" -> props)
          ))

        case _ =>
          logWarn(s"Creating generic relationship: $fromLabel -[$rel]-> $toLabel")
          Seq(CypherStatement(
            CypherTemplates.upsertEdge(fromLabel, rel, toLabel),
            Map("fromId" -> fromId, "toId" -> toId, "props" -> props)
          ))
      }
  }

  /**
   * Batch multiple writes into statements
   */
  def toCypherBatch(writes: Seq[GraphWrite]): Seq[CypherStatement] = {
    val statements = writes.flatMap(toCypher)

    // Count by type for logging
    val nodeCounts = writes.collect { case UpsertNode(label, _, _) => label }
      .groupBy(identity).mapValues(_.size)
    val edgeCounts = writes.collect { case UpsertEdge(_, _, rel, _, _, _) => rel }
      .groupBy(identity).mapValues(_.size)

    if (nodeCounts.nonEmpty) {
      logInfo(s"Batch contains nodes: ${nodeCounts.map { case (l, c) => s"$l=$c" }.mkString(", ")}")
    }
    if (edgeCounts.nonEmpty) {
      logInfo(s"Batch contains edges: ${edgeCounts.map { case (r, c) => s"$r=$c" }.mkString(", ")}")

      edgeCounts.get("RELATES_TO").foreach { count =>
        logInfo(s"★★★ RELATES_TO edges in batch: $count ★★★")
      }
    }

    statements
  }
}

/**
 * Represents an executable Cypher statement with parameters
 */
case class CypherStatement(query: String, params: Map[String, Any]) {

  /**
   * Convert params to Java map for Neo4j driver
   */
  def toJavaParams: java.util.Map[String, AnyRef] = {
    import scala.collection.JavaConverters._
    params.map { case (k, v) =>
      k -> (v match {
        case m: Map[_, _] => mapToJava(m.asInstanceOf[Map[String, Any]])
        case other        => other.asInstanceOf[AnyRef]
      })
    }.asJava
  }

  private def mapToJava(m: Map[String, Any]): java.util.Map[String, AnyRef] = {
    import scala.collection.JavaConverters._
    m.map { case (k, v) => k -> v.asInstanceOf[AnyRef] }.asJava
  }
}