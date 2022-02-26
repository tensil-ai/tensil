package tf2rtl.tools

import java.io._
import scala.collection.mutable

object GraphPrinter {
  def name(s: String)      = s.replaceAll("[:/]", "_")
  def quote(s: String)     = "\"" + s + "\""
  def quoteName(s: String) = quote(name(s))
  def html(s: String)      = "<" + s + ">"
}

class GraphPrinter(stream: OutputStream, name: String) {
  private val dataStream = new DataOutputStream(stream)
  private var depth      = 1;

  private def ident = " " * depth

  dataStream.writeBytes(s"digraph ${name} {\r\n")

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
