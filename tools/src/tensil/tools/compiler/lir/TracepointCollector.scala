/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler.lir

import scala.collection.mutable

import tensil.tools.{TracepointCondition, TracepointsMap}
import tensil.tools.compiler.{
  LIR,
  InstructionContext,
  MemoryAddress,
  MemoryAddressHelper,
  MemoryAddressRaw,
  MemoryRef,
  MemoryObject,
  InstructionAddress,
  TracepointsWriter
}

class TracepointCollector(
    conditions: Seq[TracepointCondition],
    resolveRefToObject: (MemoryRef) => Option[MemoryObject] = (ref) => None
) extends LIR {
  private val instructionTracepointsMapsBuffer =
    mutable.Map.empty[InstructionAddress, TracepointsMap]

  def instructionTracepointsMaps = instructionTracepointsMapsBuffer.toMap

  def emitWait(
      tidToWait: Int,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit = {}

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit =
    collectTracepoints(accumulatorAddress, size, accumulatorStride, context)

  def emitSIMD(
      accumulate: Boolean,
      simdOp: Int,
      simdSourceLeft: Int,
      simdSourceRight: Int,
      simdDestination: Int,
      writeAccumulatorAddress: MemoryAddress,
      readAccumulatorAddress: MemoryAddress,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit =
    collectTracepoints(writeAccumulatorAddress, 0L, 1, context)

  def emitDataMove(
      toLocal: Boolean,
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      stride: Int,
      address: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit =
    if (toLocal)
      collectTracepoints(localAddress, size, localStride, context)
    else
      collectTracepoints(address, size, stride, context)

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit = {}

  def endEmit(): Unit = {}

  private def collectTracepoints(
      address: MemoryAddress,
      size: MemoryAddressRaw,
      stride: Int,
      context: Option[InstructionContext]
  ): Unit = {
    val writer = new TracepointsWriter(conditions, resolveRefToObject)
    val step   = 1 << stride

    for (i <- 0L until size + 1)
      writer.write(
        MemoryAddress(
          tag = address.tag,
          ref = address.ref,
          raw = address.raw + (i * step)
        )
      )

    if (!writer.isEmpty)
      instructionTracepointsMapsBuffer(context.get.address.get) = writer.toMap
  }
}
