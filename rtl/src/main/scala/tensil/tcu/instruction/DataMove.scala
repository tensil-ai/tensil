/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu.instruction

import chisel3._
import chisel3.experimental.BundleLiterals._
import tensil.InstructionLayout

class DataMoveArgs(val layout: InstructionLayout) extends Bundle {
  val size       = UInt(layout.operand2SizeBits.W)
  val _unused1   = UInt(layout.operand1Padding.W)
  val accStride  = UInt(layout.stride1SizeBits.W)
  val accAddress = UInt(layout.operand1AddressSizeBits.W)
  val _unused0   = UInt(layout.operand0Padding.W)
  val memStride  = UInt(layout.stride0SizeBits.W)
  val memAddress = UInt(layout.operand0AddressSizeBits.W)
}

object DataMoveArgs {
  def apply(memAddress: BigInt, accOrDRAMAddress: BigInt)(implicit
      layout: InstructionLayout
  ): DataMoveArgs = {
    apply(memAddress, accOrDRAMAddress, 0)
  }

  def apply(
      memAddress: BigInt,
      accOrDRAMAddress: BigInt,
      size: BigInt
  )(implicit layout: InstructionLayout): DataMoveArgs =
    apply(memAddress, accOrDRAMAddress, size, 0, 0)

  def apply(
      memAddress: BigInt,
      accOrDRAMAddress: BigInt,
      size: BigInt,
      memStride: Int,
      accStride: Int
  )(implicit layout: InstructionLayout): DataMoveArgs = {
    (new DataMoveArgs(layout))
      .Lit(
        _.memAddress -> memAddress.U,
        _.accAddress -> accOrDRAMAddress.U,
        _.size       -> size.U,
        _.memStride  -> memStride.U,
        _.accStride  -> accStride.U,
        _._unused0   -> 0.U,
        _._unused1   -> 0.U,
      )
  }
}

class DataMoveFlags extends Bundle {
  import DataMoveKind.DataMoveKind
  val kind = DataMoveKind(UInt(4.W))
}

object DataMoveFlags {
  def apply(kind: UInt): DataMoveFlags =
    (new DataMoveFlags).Lit(
      _.kind -> kind
    )
}

object DataMoveKind {
  type DataMoveKind = UInt

  val dram0ToMemory = 0x0.U
  val memoryToDram0 = 0x1.U
  val dram1ToMemory = 0x2.U
  val memoryToDram1 = 0x3.U
  // 0x4 - 0xb are unused
  val accumulatorToMemory = 0xc.U
  val memoryToAccumulator = 0xd.U
  // 0xe is unused
  val memoryToAccumulatorAccumulate = 0xf.U

  val all = Array(
    dram0ToMemory,
    memoryToDram0,
    dram1ToMemory,
    memoryToDram1,
    accumulatorToMemory,
    memoryToAccumulator,
    memoryToAccumulatorAccumulate
  )

  def apply(kind: UInt): DataMoveKind = kind

  // def apply(kind: UInt): DataMoveKind = {
  //   if (kind.isLit()) {
  //     new DataMoveKind().Lit(
  //       _.kind -> kind,
  //     )
  //   } else {
  //     val w = Wire(new DataFlowControl())
  //     w.kind := kind
  //     w
  //   }
  // }

  def isValid(kind: UInt): Bool = {
    kind <= memoryToDram1 ||
    kind === accumulatorToMemory ||
    kind === memoryToAccumulator ||
    kind === memoryToAccumulatorAccumulate
  }
}
