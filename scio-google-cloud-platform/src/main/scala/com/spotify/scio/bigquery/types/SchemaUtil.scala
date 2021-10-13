/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.bigquery.types

import java.util.{List => JList}
import com.google.api.services.bigquery.model.{TableFieldSchema, TableSchema}
import org.apache.avro.Schema
import org.apache.avro.Schema.{Type => AvroSchemaType}

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.jdk.CollectionConverters._

/** Utility for BigQuery schemas. */
object SchemaUtil {

  /** Convert schema to case class definitions. */
  def toPrettyString(schema: TableSchema, name: String, indent: Int): String =
    "@BigQueryType.toTable\n" +
      getCaseClass(schema.getFields, name, indent)

  private def getRawType(tfs: TableFieldSchema, indent: Int): (String, Seq[String]) = {
    val name = tfs.getType match {
      case "BOOLEAN"           => "Boolean"
      case "INTEGER" | "INT64" => "Long"
      case "FLOAT" | "FLOAT64" => "Double"
      case "STRING"            => "String"
      case "NUMERIC"           => "BigDecimal"
      case "BYTES"             => "ByteString"
      case "TIMESTAMP"         => "Instant"
      case "DATE"              => "LocalDate"
      case "TIME"              => "LocalTime"
      case "DATETIME"          => "LocalDateTime"
      case "RECORD" | "STRUCT" => NameProvider.getUniqueName(tfs.getName)
      case t                   => throw new IllegalArgumentException(s"Type: $t not supported")
    }
    if (tfs.getType == "RECORD") {
      val nested = getCaseClass(tfs.getFields, name, indent)
      (name, Seq(nested))
    } else {
      (name, Seq.empty)
    }
  }

  private def getFieldType(tfs: TableFieldSchema, indent: Int): (String, Seq[String]) = {
    val (rawType, nested) = getRawType(tfs, indent)
    val fieldType = tfs.getMode match {
      case "NULLABLE" | null => "Option[" + rawType + "]"
      case "REQUIRED"        => rawType
      case "REPEATED"        => "List[" + rawType + "]"
    }
    (fieldType, nested)
  }

  private def getCaseClass(fields: JList[TableFieldSchema], name: String, indent: Int): String = {
    val xs = fields.asScala
      .map { f =>
        val (fieldType, nested) = getFieldType(f, indent)
        (escapeNameIfReserved(f.getName) + ": " + fieldType, nested)
      }
    val lines = xs.map(_._1)
    val nested = xs.flatMap(_._2)

    val sb = new StringBuilder
    sb.append(s"case class $name(")
    if (indent > 0) {
      sb.append("\n")
    }
    val body = if (indent > 0) {
      val w = " " * indent
      lines.map(w + _).mkString(",\n")
    } else {
      lines.mkString(", ")
    }
    sb.append(body)
    sb.append(")")
    (sb.toString +: nested).mkString("\n")
  }

  private[types] def escapeNameIfReserved(name: String): String =
    if (scalaReservedWords.contains(name)) {
      s"`$name`"
    } else {
      name
    }

  private[types] val scalaReservedWords = Seq(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "extends",
    "false",
    "final",
    "finally",
    "for",
    "forSome",
    "if",
    "implicit",
    "import",
    "lazy",
    "match",
    "new",
    "null",
    "object",
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "this",
    "throw",
    "trait",
    "try",
    "true",
    "type",
    "val",
    "var",
    "while",
    "with",
    "yield"
  )

  /**
   * Traverses (using BFS) Avro records tree and returns the set of all path prefixed names of all
   * fields in all records including names of record fields. E.g. for schema:
   *
   * { "type": "record", "fields": [ { "name": "r1", "type": { "type": "record", "fields": [ {
   * "name": "str", "type": "string" }, { "name": "int", "type": "long" } ] } } ] }
   *
   * It would return Set(r1, r1.str, r1.int)
   */
  private[scio] def recordPathPrefixedFieldNames(schema: Schema): Set[String] = {
    @tailrec
    def go(records: Queue[(Schema, String)], names: Set[String]): Set[String] = {
      if (records.isEmpty) {
        names
      } else {
        records.dequeueOption match {
          case Some(((record, prefix), queue)) =>
            val (newRecords, newNames) = record.getFields.asScala.foldLeft((queue, names)) {
              case ((q, ns), field) =>
                val recordSchema = field.schema()
                val recordPrefix = prefix + field.name()
                (
                  if (recordSchema.getType == AvroSchemaType.RECORD)
                    q.enqueue((recordSchema, recordPrefix + "."))
                  else q,
                  ns + recordPrefix
                )
            }
            go(newRecords, newNames)
          case None => names
        }
      }
    }

    if (schema.getType != AvroSchemaType.RECORD) {
      Set.empty
    } else {
      val queue = Queue((schema, ""))
      go(queue, Set.empty)
    }
  }
}
