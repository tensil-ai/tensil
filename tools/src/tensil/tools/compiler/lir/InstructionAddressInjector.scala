/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler.lir

import tensil.tools.compiler.{LIR, InstructionContext, MemoryAddress, MemoryAddressRaw}
import tensil.tools.compiler.InstructionAddress

class InstructionAddressInjector(targetLir: LIR) extends LIR {
  private var instructionOffset: InstructionAddress = InstructionAddress.Zero
  private def inject(context: Option[InstructionContext]): Option[InstructionContext] = {
    val r = InstructionContext.injectInstructionAddress(context, instructionOffset)
    instructionOffset += InstructionAddress.One
    r
  }

  def instructionsCount = instructionOffset

  def emitWait(tid: Int, tidToWait: Int, context: Option[InstructionContext]): Unit =
    targetLir.emitWait(tid, tidToWait, inject(context))

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
    targetLir.emitMatMul(
      accumulate: Boolean,
      localStride,
      localAddress,
      accumulatorStride,
      accumulatorAddress,
      size,
      tid,
      inject(context)
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
    targetLir.emitSIMD(
      accumulate,
      simdOp,
      simdSourceLeft,
      simdSourceRight,
      simdDestination,
      writeAccumulatorAddress,
      readAccumulatorAddress,
      tid,
      inject(context)
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
    targetLir.emitDataMove(
      toLocal,
      accumulate,
      localStride,
      localAddress,
      stride,
      address,
      size,
      tid,
      inject(context)
    )

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit =
    targetLir.emitLoadWeights(
      localStride,
      localAddress,
      size,
      tid,
      inject(context)
    )

  def endEmit(): Unit = {}
}
