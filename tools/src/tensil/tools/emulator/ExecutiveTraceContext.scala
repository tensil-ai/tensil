/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.emulator

import scala.collection.mutable
import tensil.tools.{TraceContext, TracepointsMap}
import tensil.tools.compiler.{
  MemoryAddress,
  MemoryAddressHelper,
  MemoryObject,
  MemoryTag,
  MemoryAddressRaw,
  InstructionAddress
}
import scala.io.Source

object ShowDiffOption {
  val Golden: Int   = 0
  val Hardware: Int = 1
  val Delta: Int    = 2
}

object ExecutiveTraceContext {
  val default = new ExecutiveTraceContext()
}

class ExecutiveTraceContext(
    val showDiffOption: Int = ShowDiffOption.Golden,
    val maxError: Float = 0
) extends TraceContext {
  private val tracepointsAtInstructionOffsets =
    mutable.Map
      .empty[InstructionAddress, mutable.Map[MemoryAddress, mutable.ArrayBuffer[
        MemoryObject
      ]]]

  private val taggedHardwareVectors =
    mutable.Map.empty[MemoryTag, mutable.Map[MemoryAddressRaw, Array[Float]]]

  private val objectVectors =
    mutable.Map.empty[String, Array[Array[Float]]]

  def diffWithHardwareCsv(
      fileName: String,
      tag: MemoryTag,
      base: MemoryAddressRaw = 0
  ) = {
    val source  = Source.fromFile(fileName)
    val vectors = source.getLines().map(_.split(",").map(_.toFloat)).toArray
    source.close()

    var raw = base
    val hardwareVectors = taggedHardwareVectors.getOrElseUpdate(
      tag,
      mutable.Map.empty[MemoryAddressRaw, Array[Float]]
    )

    for (vector <- vectors) {
      hardwareVectors(raw) = vector
      raw += 1
    }
  }

  def diffWithObjectCsv(name: String, fileName: String) = {
    val source = Source.fromFile(fileName)
    objectVectors(name) =
      source.getLines().map(_.split(",").map(_.toFloat)).toArray
    source.close()
  }

  def findTracepointsAtInstructionOffset(
      instructionOffset: InstructionAddress
  ): Option[TracepointsMap] =
    tracepointsAtInstructionOffsets
      .get(instructionOffset)
      .map(_.mapValues(_.toList).toMap)

  def findObjectVectors(name: String): Option[Array[Array[Float]]] =
    objectVectors.get(name)

  def findHardwareVector(address: MemoryAddress): Option[Array[Float]] = {
    val hardwareVectors = taggedHardwareVectors.get(address.tag)

    if (hardwareVectors.isDefined)
      hardwareVectors.get.get(address.raw)
    else
      None
  }

  override def blendObjects(
      obj: MemoryObject,
      blendeeNames: Seq[String]
  ): Unit = {
    for (
      tracepoints              <- tracepointsAtInstructionOffsets.values;
      (address, objectsBuffer) <- tracepoints
    )
      if (
        obj.span.contains(address) && objectsBuffer
          .exists(obj => blendeeNames.contains(obj.name))
      )
        objectsBuffer += obj
  }

  override def emitTracepoints(
      instructionOffset: InstructionAddress,
      tracepointsMap: TracepointsMap
  ): Unit = {
    val tracepointsMapToMerge = tracepointsAtInstructionOffsets.getOrElseUpdate(
      instructionOffset,
      mutable.Map.empty[MemoryAddress, mutable.ArrayBuffer[MemoryObject]]
    )

    for ((address, objects) <- tracepointsMap)
      tracepointsMapToMerge.getOrElseUpdate(
        address,
        mutable.ArrayBuffer.empty[MemoryObject]
      ) ++= objects
  }
}
