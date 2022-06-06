/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.emulator

import scala.collection.mutable

import tensil.{TableLine, TablePrinter}
import tensil.tools.compiler.{
  MemoryTag,
  MemoryAddress,
  MemoryObject,
  MemoryAddressHelper,
  InstructionAddress
}

class ExecutiveTrace(context: ExecutiveTraceContext) {
  private case class TraceKey(
      instructionOffset: InstructionAddress,
      objectName: String,
      addressTag: MemoryTag
  )

  private case class Trace(
      instructionOffset: InstructionAddress,
      vector: Array[Float]
  )

  private val traceObjects = mutable.Map.empty[TraceKey, MemoryObject]
  private val traces = mutable.Map
    .empty[TraceKey, mutable.Map[MemoryAddress, mutable.ArrayBuffer[Trace]]]

  private val ANSI_RESET = "\u001B[0m"
  private val ANSI_RED   = "\u001B[31m"
  private val ANSI_GREEN = "\u001B[32m"

  def runTrace(
      instructionOffset: InstructionAddress,
      executive: Executive
  ): Unit = {
    val tracepoints =
      context.findTracepointsAtInstructionOffset(instructionOffset)

    if (tracepoints.isDefined)
      for ((address, objects) <- tracepoints.get) {
        val vector = address.tag match {
          case MemoryTag.Accumulators =>
            executive.peekAccumulator(address.raw)
          case MemoryTag.Local =>
            executive.peekLocal(address.raw)
          case MemoryTag.Vars => executive.peekDRAM0(address.raw)
          case MemoryTag.Consts =>
            executive.peekDRAM1(address.raw)
        }

        for (obj <- objects) {
          val key = TraceKey(
            instructionOffset = instructionOffset,
            objectName = obj.name,
            addressTag = address.tag
          )

          if (!traceObjects.isDefinedAt(key))
            traceObjects(key) = obj

          val addressTraces = traces.getOrElseUpdate(
            key,
            mutable.Map.empty[MemoryAddress, mutable.ArrayBuffer[Trace]]
          )

          addressTraces.getOrElseUpdate(
            address,
            mutable.ArrayBuffer.empty[Trace]
          ) += Trace(
            instructionOffset = instructionOffset,
            vector = vector
          )
        }
      }
  }

  private def findGoldenVector(
      obj: MemoryObject,
      address: MemoryAddress
  ): Option[Array[Float]] = {
    val vectors = context.findObjectVectors(obj.name)
    val r = if (vectors.isDefined) {
      val index = obj.span.indexOf(address)

      if (index != -1)
        Some(vectors.get(index))
      else
        None
    } else
      None

    if (r.isDefined)
      r
    else
      context.findHardwareVector(address)
  }

  private def indexToString(index: Int) =
    if (index == -1) "????????" else f"${index}%08d"

  private def printVector(
      tb: TablePrinter,
      obj: MemoryObject,
      index: Int,
      address: MemoryAddress,
      trace: Trace
  ): Unit = {
    val goldenVector = findGoldenVector(obj, address)

    if (goldenVector.isDefined) {
      tb.addLine(
        TableLine(
          indexToString(index),
          s"P[${trace.instructionOffset}]",
          MemoryAddressHelper(address).toString(),
          goldenVector.get
            .zip(trace.vector)
            .grouped(8)
            .map(_.map({
              case (vGolden, vHardware) => {
                val delta    = vGolden - vHardware
                val mismatch = Math.abs(delta) > context.maxError
                val s =
                  if (mismatch) context.showDiffOption match {
                    case ShowDiffOption.Golden   => f"$vHardware%.4f"
                    case ShowDiffOption.Hardware => f"$vGolden%.4f"
                    case ShowDiffOption.Delta    => f"($delta%.4f)"
                  }
                  else f"$vHardware%.4f"
                val sColored = (if (mismatch) ANSI_RED
                                else ANSI_GREEN) + s + ANSI_RESET
                " " * (12 - s.length()) + sColored
              }
            }).mkString)
            .toIterable
        )
      )
    } else {
      tb.addLine(
        TableLine(
          indexToString(index),
          s"P[${trace.instructionOffset}]",
          MemoryAddressHelper(address),
          trace.vector
            .grouped(8)
            .map(_.map(v => {
              val s = f"$v%.4f"
              " " * (12 - s.length()) + s
            }).mkString)
            .toIterable
        )
      )
    }
  }

  def printTrace(maxVectors: Int = 100) {
    for ((key, obj) <- traceObjects.toSeq.sortBy(_._1.instructionOffset)) {
      val tb = new TablePrinter(
        Some(
          s"TRACE FOR ${obj.name} IN ${MemoryTag.toString(key.addressTag)} MEMORY"
        )
      )
      val indexes   = mutable.Map.empty[String, Int]
      val objTraces = traces(key)

      if (obj.span.contains(objTraces.head._1))
        for (index <- 0 until Math.min(obj.span.size, maxVectors)) {
          val address       = obj.span(index)
          val addressTraces = objTraces.get(address)

          if (addressTraces.isDefined)
            printVector(
              tb,
              obj,
              index,
              address,
              addressTraces.get.sortBy(_.instructionOffset).last
            )
          else
            tb.addLine(
              TableLine(
                indexToString(index),
                "-"
              )
            )
        }
      else
        for (
          (address, addressTraces) <-
            traces(key).toSeq
              .sortBy(_._1)
              .take(maxVectors)
        ) {
          printVector(
            tb,
            obj,
            -1,
            address,
            addressTraces.sortBy(_.instructionOffset).last
          )
        }

      print(tb)
    }
  }
}
