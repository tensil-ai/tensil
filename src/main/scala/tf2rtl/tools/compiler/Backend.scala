package tf2rtl.tools.compiler

import java.io._
import scala.collection.mutable
import chisel3.UInt
import tf2rtl.tools.{
  TablePrinter,
  TableLine,
  TraceContext,
  TracepointCondition
}
import tf2rtl.ArchitectureDataType

class Backend(
    programStream: OutputStream,
    layout: InstructionLayout,
    dataType: ArchitectureDataType,
    padding: Map[(UInt, UInt), Int] = Map.empty[(UInt, UInt), Int],
    printProgramStream: Option[DataOutputStream] = None,
    printComments: Boolean = false,
    tracepointConditions: Seq[TracepointCondition] = Nil,
    tracepointResolveRefToObject: (MemoryRef) => Option[MemoryObject] = (ref) =>
      None,
    traceContext: TraceContext = TraceContext.empty,
) {
  private val programStringWriter    = new StringWriter()
  private val instructionLinesBuffer = mutable.ArrayBuffer.empty[TableLine]

  private var instructionOffset: InstructionAddress = InstructionAddress.Zero

  def mkStream(
      name: String,
      stats: Option[BackendStats] = None
  ): BackendStream =
    new BackendStream(
      name = name,
      layout = layout,
      dataType = dataType,
      padding = padding,
      printProgram = printProgramStream.isDefined,
      printComments = printComments,
      stats = stats,
      tracepointConditions = tracepointConditions,
      tracepointResolveRefToObject = tracepointResolveRefToObject
    )

  def writeStream(
      stream: BackendStream,
      buffer: Array[Byte] = Array.fill[Byte](256)(0)
  ): Unit = {
    stream.closeAndWriteToOutputStream(programStream, buffer)

    if (printProgramStream.isDefined) {
      val tb = new TablePrinter(Some(stream.name))

      var i = 0
      for (instructionLine <- stream.instructionLines) {
        val printProgramOffset = instructionOffset + i

        tb.addLine(
          TableLine(
            s"P[$printProgramOffset]",
            instructionLine
          )
        )

        i += 1
      }

      printProgramStream.get.writeBytes(tb.toString())
    }

    for (
      (offset, instructionTracepointsMap) <- stream.instructionTracepointsMaps
    ) {
      traceContext.emitTracepoints(
        instructionOffset + offset,
        instructionTracepointsMap
      )
    }

    instructionOffset += stream.instructionsCount
  }

  def instructionsCount = instructionOffset
}
