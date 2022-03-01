package tensil.tools.compiler

import java.io._
import _root_.tensil.tools.GraphPrinter

import org.tensorflow.framework.node_def.NodeDef

class TfFrontendGraphPrinter(stream: OutputStream, name: String, arraySize: Int)
    extends GraphPrinter(stream, name) {
  var layerName: Option[String] = None

  def startLayer(name: String): Unit = {
    layerName = Some(name)
  }

  def endLayer() = layerName = None

  def printOp(
      nodeDef: NodeDef,
      outputs: Seq[MemoryObject],
      inputs: Seq[MemoryObject] = Nil,
      params: Seq[(String, MemoryObject)] = Nil
  ): Unit = {
    val name = nodeDef.name
    val op   = nodeDef.op

    val subTitle = new StringBuffer(s"Op: $op")
    for ((paramName, paramObj) <- params) {
      subTitle.append(s"<BR/>$paramName: ${paramObj.dims}")
    }

    def mkOutputPort(obj: MemoryObject, i: Int): String = {
      val colonParts = obj.name.split(":")
      val port       = if (colonParts.size != 2) "0" else colonParts(1)

      s"""<TD PORT="$port"><I>#$i</I></TD>"""
    }

    val ouputPorts = outputs.zipWithIndex.map {
      case (obj, i) => mkOutputPort(obj, i)
    }.mkString

    if (layerName.isDefined)
      printStartSubGraph(
        GraphPrinter.quoteName(s"cluster_${layerName.get}"),
        GraphPrinter.quote(shortName(layerName.get))
      )

    printNode(
      GraphPrinter.quote(name),
      GraphPrinter.html(
        s"""<TABLE PORT="in" BORDER="0" CELLBORDER="1" CELLSPACING="0"><TR><TD COLSPAN="${outputs.size}"><B>${shortName(
          name
        )}</B></TD></TR><TR><TD COLSPAN="${outputs.size}">$subTitle</TD></TR><TR>$ouputPorts</TR></TABLE>"""
      ),
      "plaintext"
    )

    if (layerName.isDefined)
      printEndSubGraph()

    for (input <- inputs) {
      val (edgeName, edgePort) = parseEdgePort(input.name)

      printEdge(
        s"${GraphPrinter.quote(edgeName)}:${GraphPrinter.quote(edgePort)}",
        s"${GraphPrinter.quote(name)}:${GraphPrinter.quote("in")}",
        GraphPrinter.quote(s"${input.name}\\n${input.dims}")
      )
    }
  }

  def printInputPost(obj: MemoryObject): Unit = {
    val inputPostName = s"Input/${obj.name}"

    printNode(
      GraphPrinter.quote(inputPostName),
      GraphPrinter.quote(""),
      "circle"
    )
    printEdge(
      GraphPrinter.quote(inputPostName),
      GraphPrinter.quote(obj.name),
      GraphPrinter.quote(s"${obj.name}\\n${obj.dims}")
    )
  }

  def printOutputPost(
      obj: MemoryObject
  ): Unit = {
    val outputPostName = s"Output/${obj.name}"

    printNode(
      GraphPrinter.quote(outputPostName),
      GraphPrinter.quote(""),
      "doublecircle"
    )

    val (edgeName, edgePort) = parseEdgePort(obj.name)

    printEdge(
      s"${GraphPrinter.quote(edgeName)}:${GraphPrinter.quote(edgePort)}",
      GraphPrinter.quote(outputPostName),
      GraphPrinter.quote(s"${obj.name}\\n${obj.dims}")
    )
  }

  private def parseEdgePort(name: String): (String, String) = {
    val colonParts = name.split(":")

    if (colonParts.size != 2) (name, "0")
    else (colonParts(0), colonParts(1))
  }

  private def shortName(name: String): String = {
    val slashParts = name.split("/")

    if (slashParts.size > 2)
      slashParts(slashParts.size - 2)
    else
      name
  }
}
