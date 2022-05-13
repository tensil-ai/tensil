/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import tensil.InstructionLayout

class LIREstimator(layout: InstructionLayout, stats: Stats) extends LIR {
  val estimator = new Estimator(layout)

  def emitNoOp(): Unit = {
    stats.countInstruction(
      "Wait",
      estimator.estimateCyclesAndEnergy(Opcode.Wait)
    )
  }

  def emitWait(tidToWait: Int): Unit = {
    stats.countInstruction(
      "Wait",
      estimator.estimateCyclesAndEnergy(Opcode.Wait)
    )
  }

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit = {
    val mnemonic = "MatMul"

    stats.countInstruction(
      mnemonic,
      estimator.estimateCyclesAndEnergy(Opcode.MatMul, Some(size)),
      Some(size)
    )
    if (localAddress.tag != MemoryTag.Zeroes)
      stats.countStride(mnemonic, 0, localStride, size)
    stats.countStride(mnemonic, 1, accumulatorStride, size)
  }

  def emitSIMD(
      accumulate: Boolean,
      simdOp: Int,
      simdSourceLeft: Int,
      simdSourceRight: Int,
      simdDestination: Int,
      writeAccumulatorAddress: MemoryAddress,
      readAccumulatorAddress: MemoryAddress
  ): Unit = {
    stats.countInstruction(
      "SIMD",
      estimator.estimateCyclesAndEnergy(Opcode.SIMD)
    )
  }

  def emitDataMove(
      toLocal: Boolean,
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      stride: Int,
      address: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit = {
    val flags = LIRGen.mkDataMoveFlags(toLocal, accumulate, address.tag)
    val suffix = flags match {
      case DataMoveFlags.LocalToDRAM0       => "(LocalToDRAM0)"
      case DataMoveFlags.LocalToDRAM1       => "(LocalToDRAM1)"
      case DataMoveFlags.DRAM0ToLocal       => "(DRAM0ToLocal)"
      case DataMoveFlags.DRAM1ToLocal       => "(DRAM1ToLocal)"
      case DataMoveFlags.AccumulatorToLocal => "(AccToLocal)"
      case DataMoveFlags.LocalToAccumulator |
          DataMoveFlags.LocalToAccumulatorAccumulate =>
        "(LocalToAcc)"
    }

    val mnemonicWithSuffix = "DataMove" + suffix

    stats.countInstruction(
      mnemonicWithSuffix,
      estimator.estimateCyclesAndEnergy(Opcode.DataMove, Some(size), flags),
      Some(size)
    )
    stats.countStride(mnemonicWithSuffix, 0, localStride, size)
    stats.countStride(mnemonicWithSuffix, 1, stride, size)
  }

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit = {
    val mnemonic = "LoadWeights"

    stats.countInstruction(
      mnemonic,
      estimator.estimateCyclesAndEnergy(Opcode.LoadWeights, Some(size)),
      Some(size)
    )

    if (localAddress.tag != MemoryTag.Zeroes)
      stats.countStride(mnemonic, 0, localStride, size)
  }
}
