/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools

import java.io._
import scala.collection.mutable

object GraphPrinter {
  def name(s: String)      = s.replaceAll("[:/]", "_")
  def quote(s: String)     = "\"" + s + "\""
  def quoteName(s: String) = quote(name(s))
  def html(s: String)      = "<" + s + ">"
}

abstract class GraphPrinter(stream: OutputStream, name: String) {
  private val dataStream = new DataOutputStream(stream)
  private var depth      = 1;

  private def ident = " " * depth

  dataStream.writeBytes(s"digraph ${name} {\r\n")

  def startLayer(name: String): Unit
  def endLayer(): Unit

  def printStartSubGraph(name: String, label: String) = {
    dataStream.writeBytes(
      s"${ident}subgraph ${name} {\r\n"
    )

    depth += 1

    dataStream.writeBytes(
      s"${ident}label=${label};\r\n"
    )
  }

  def printEndSubGraph() = {
    depth -= 1
    dataStream.writeBytes(s"${ident}}\r\n")
  }

  def printNode(name: String, label: String, shape: String) =
    dataStream.writeBytes(
      s"${ident}${name} [shape=$shape label=${label}];\r\n"
    )

  def printEdge(from: String, to: String, label: String) =
    dataStream.writeBytes(
      s"${ident}${from} -> ${to} [label=${label}];\r\n"
    )

  def endPrint = dataStream.writeBytes("}\r\n")
}
