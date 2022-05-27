/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler.lir

import scala.collection.mutable

import tensil.tools.{TraceContext}
import tensil.tools.compiler.{
  LIR,
  InstructionContext,
  MemoryAddress,
  MemoryAddressRaw
}

class TracepointEmitter(traceContext: TraceContext) extends LIR {
  def emitWait(
      tidToWait: Int,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit = emitTracepoints(context)

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit = emitTracepoints(context)

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
  ): Unit = emitTracepoints(context)

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
  ): Unit = emitTracepoints(context)

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit = emitTracepoints(context)

  def endEmit(): Unit = {}

  private def emitTracepoints(context: Option[InstructionContext]): Unit =
    if (
      context.isDefined && context.get.address.isDefined && context.get.tracepointsMaps.isDefined
    ) {
      val address        = context.get.address.get
      val tracepointsMap = context.get.tracepointsMaps.get.get(address)

      if (tracepointsMap.isDefined)
        traceContext.emitTracepoints(
          address,
          tracepointsMap.get
        )
    }
}
