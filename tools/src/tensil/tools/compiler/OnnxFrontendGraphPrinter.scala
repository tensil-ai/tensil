/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io._
import _root_.tensil.tools.GraphPrinter

import onnx.onnx.NodeProto

class OnnxFrontendGraphPrinter(
    stream: OutputStream,
    name: String,
    arraySize: Int,
    outputNodeNames: Map[String, Array[String]]
) extends GraphPrinter(stream, name) {
  var layerName: Option[String] = None

  def startLayer(name: String): Unit = {
    layerName = Some(name)
  }

  def endLayer() = layerName = None

  private def beforePrintNode(): Unit =
    if (layerName.isDefined)
      printStartSubGraph(
        GraphPrinter.quoteName(s"cluster_${layerName.get}"),
        GraphPrinter.quote(shortName(layerName.get))
      )

  private def afterPrintNode(): Unit =
    if (layerName.isDefined)
      printEndSubGraph()

  def printOp(
      nodeProto: NodeProto,
      outputs: Seq[MemoryObject],
      inputs: Seq[MemoryObject] = Nil,
      params: Seq[(String, MemoryObject)] = Nil
  ): Unit = {
    val name = nodeProto.name.get
    val op   = nodeProto.opType.get

    val slashParts = name.split("/")

    val title = new StringBuffer(s"${shortName(name)}\\n\\nOp: $op")
    for ((paramName, paramObj) <- params) {
      title.append(s"\\n$paramName: ${paramObj.dims}")
    }

    def mkPort(obj: MemoryObject, i: Int) =
      s"<${GraphPrinter.name(obj.name)}> #$i"

    val inputPorts =
      inputs.zipWithIndex.map { case (obj, i) => mkPort(obj, i) }.mkString("|")
    val outputPorts =
      outputs.zipWithIndex.map { case (obj, i) => mkPort(obj, i) }.mkString("|")

    val colspan = Math.max(inputs.size, outputs.size)

    beforePrintNode()

    printNode(
      GraphPrinter.quoteName(name),
      GraphPrinter.quote(s"{{$inputPorts}|$title|{$outputPorts}}"),
      "record"
    )

    afterPrintNode()

    for (input <- inputs)
      outputNodeNames.get(input.name) match {
        case Some(outputNodeNames) =>
          for (outputNodeName <- outputNodeNames)
            printEdge(
              s"${GraphPrinter.quoteName(outputNodeName)}:${GraphPrinter
                .quoteName(input.name)}",
              s"${GraphPrinter.quoteName(name)}:${GraphPrinter.quoteName(input.name)}",
              GraphPrinter.quote(s"${input.name}\\n${input.dims}")
            )

        case None =>
          printEdge(
            s"${GraphPrinter.quoteName(input.name)}",
            s"${GraphPrinter.quoteName(name)}:${GraphPrinter.quoteName(input.name)}",
            GraphPrinter.quote(s"${input.name}\\n${input.dims}")
          )
      }
  }

  def printInputPost(obj: MemoryObject): Unit = {
    beforePrintNode()

    printNode(
      GraphPrinter.quoteName(obj.name),
      GraphPrinter.quote(""),
      "circle"
    )

    afterPrintNode()
  }

  def printOutputPost(
      obj: MemoryObject
  ): Unit = {
    beforePrintNode()

    printNode(
      GraphPrinter.quoteName(obj.name),
      GraphPrinter.quote(""),
      "doublecircle"
    )

    afterPrintNode()

    for (outputNodeName <- outputNodeNames(obj.name))
      printEdge(
        s"${GraphPrinter.quoteName(outputNodeName)}:${GraphPrinter.quoteName(obj.name)}",
        s"${GraphPrinter.quoteName(obj.name)}",
        GraphPrinter.quote(s"${obj.name}\\n${obj.dims}")
      )
  }

  private def shortName(name: String): String = {
    val slashParts = name.split("/")

    if (slashParts.size > 2)
      slashParts(slashParts.size - 2)
    else
      name
  }
}
