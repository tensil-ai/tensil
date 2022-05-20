/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import scala.collection.mutable

import tensil.tools.{TracepointCondition, TracepointsMap}

class LIRTracepointCollector(
    conditions: Seq[TracepointCondition],
    resolveRefToObject: (MemoryRef) => Option[MemoryObject] = (ref) => None
) extends LIR {
  private var instructionOffset: InstructionAddress = InstructionAddress.Zero
  private val instructionTracepointsMapsBuffer =
    mutable.Map.empty[InstructionAddress, TracepointsMap]

  def instructionsCount          = instructionOffset
  def instructionTracepointsMaps = instructionTracepointsMapsBuffer.toMap

  def emitWait(tidToWait: Int, tid: Int): Unit = incInstructionsCount()

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int
  ): Unit =
    emitTracepoints(accumulatorAddress, size, accumulatorStride)

  def emitSIMD(
      accumulate: Boolean,
      simdOp: Int,
      simdSourceLeft: Int,
      simdSourceRight: Int,
      simdDestination: Int,
      writeAccumulatorAddress: MemoryAddress,
      readAccumulatorAddress: MemoryAddress,
      tid: Int
  ): Unit =
    emitTracepoints(writeAccumulatorAddress, 0L, 1)

  def emitDataMove(
      toLocal: Boolean,
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      stride: Int,
      address: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int
  ): Unit =
    if (toLocal)
      emitTracepoints(localAddress, size, localStride)
    else
      emitTracepoints(address, size, stride)

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int
  ): Unit = incInstructionsCount()

  def endEmit(): Unit = {}

  private def incInstructionsCount(): Unit = instructionOffset += 1

  private def emitTracepoints(
      address: MemoryAddress,
      size: MemoryAddressRaw,
      stride: Int
  ) {
    incInstructionsCount()

    val tracepointsWriter =
      new TracepointsWriter(conditions, resolveRefToObject)
    val step = 1 << stride

    for (i <- 0L until size + 1) {
      tracepointsWriter.emitWrite(
        MemoryAddress(
          tag = address.tag,
          ref = address.ref,
          raw = address.raw + (i * step)
        )
      )

      val tracepointsMap = tracepointsWriter.toMap()

      if (!tracepointsMap.isEmpty)
        instructionTracepointsMapsBuffer(instructionOffset) = tracepointsMap
    }
  }
}
