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
  val dataFlowControl = UInt(4.W)
}

object DataMoveFlags {
  def apply(dataFlowControl: UInt): DataMoveFlags =
    (new DataMoveFlags).Lit(
      _.dataFlowControl -> dataFlowControl
    )
}
