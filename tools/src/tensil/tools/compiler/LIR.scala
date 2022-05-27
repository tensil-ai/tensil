/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

abstract trait LIR {
  final def emitNoOp(tid: Int = 0, context: Option[InstructionContext] = None) =
    emitWait(tid, tid, context)

  def emitWait(
      tidToWait: Int,
      tid: Int = 0,
      context: Option[InstructionContext] = None
  ): Unit

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int = 0,
      context: Option[InstructionContext] = None
  ): Unit

  def emitSIMD(
      accumulate: Boolean,
      simdOp: Int,
      simdSourceLeft: Int,
      simdSourceRight: Int,
      simdDestination: Int,
      writeAccumulatorAddress: MemoryAddress,
      readAccumulatorAddress: MemoryAddress,
      tid: Int = 0,
      context: Option[InstructionContext] = None
  ): Unit

  def emitDataMove(
      toLocal: Boolean,
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      stride: Int,
      address: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int = 0,
      context: Option[InstructionContext] = None
  ): Unit

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int = 0,
      context: Option[InstructionContext] = None
  ): Unit

  def endEmit(): Unit
}
