/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler.lir

import tensil.tools.compiler.{LIR, InstructionContext, MemoryAddress, MemoryAddressRaw}

class Broadcast(lirs: LIR*) extends LIR {
  def emitWait(tid: Int, tidToWait: Int, context: Option[InstructionContext]): Unit =
    lirs.foreach(_.emitWait(tid, tidToWait, context))

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
    lirs.foreach(
      _.emitMatMul(
        accumulate,
        localStride,
        localAddress,
        accumulatorStride,
        accumulatorAddress,
        size,
        tid,
        context
      )
    )

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
    lirs.foreach(
      _.emitSIMD(
        accumulate,
        simdOp,
        simdSourceLeft,
        simdSourceRight,
        simdDestination,
        writeAccumulatorAddress,
        readAccumulatorAddress,
        tid,
        context
      )
    )

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
    lirs.foreach(
      _.emitDataMove(
        toLocal,
        accumulate,
        localStride,
        localAddress,
        stride,
        address,
        size,
        tid,
        context
      )
    )

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit =
    lirs.foreach(
      _.emitLoadWeights(localStride, localAddress, size, tid, context)
    )

  def endEmit(): Unit = lirs.foreach(_.endEmit())
}
