/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io._
import scala.collection.mutable
import tensil.tools.{
  TraceContext,
  TracepointCondition
}
import tensil.{TablePrinter, TableLine, ArchitectureDataType, InstructionLayout}

class Backend(
    programStream: OutputStream,
    layout: InstructionLayout,
    dataType: ArchitectureDataType,
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
