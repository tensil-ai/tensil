package tensil.tcu.instruction

import chisel3._
import chisel3.experimental.BundleLiterals._
import tensil.InstructionLayout

class MatMulArgs(val layout: InstructionLayout) extends Bundle {
  val size       = UInt(layout.operand2SizeBits.W)
  val _unused1   = UInt(layout.operand1Padding.W)
  val accStride  = UInt(layout.stride1SizeBits.W)
  val accAddress = UInt(layout.operand1AddressSizeBits.W)
  val _unused0   = UInt(layout.operand0Padding.W)
  val memStride  = UInt(layout.stride0SizeBits.W)
  val memAddress = UInt(layout.operand0AddressSizeBits.W)
}

object MatMulArgs {
  def apply(memAddress: BigInt, accAddress: BigInt)(implicit
      layout: InstructionLayout
  ): MatMulArgs = {
    apply(memAddress, accAddress, 0, 0, 0)
  }

  def apply(memAddress: BigInt, accAddress: BigInt, size: BigInt)(implicit
      layout: InstructionLayout
  ): MatMulArgs = apply(memAddress, accAddress, size, 0, 0)

  def apply(
      memAddress: BigInt,
      accAddress: BigInt,
      size: BigInt,
      memStride: Int,
      accStride: Int,
  )(implicit layout: InstructionLayout): MatMulArgs = {
    new MatMulArgs(layout)
      .Lit(
        _.memAddress -> memAddress.U,
        _.accAddress -> accAddress.U,
        _.size       -> size.U,
        _.memStride  -> memStride.U,
        _.accStride  -> accStride.U,
        _._unused0   -> 0.U,
        _._unused1   -> 0.U,
      )
  }
}

class MatMulFlags extends Bundle {
  val _unused    = UInt(2.W)
  val zeroes     = Bool()
  val accumulate = Bool()
}

object MatMulFlags {
  def apply(accumulate: Boolean): MatMulFlags =
    apply(accumulate, zeroes = false)

  def apply(accumulate: Boolean, zeroes: Boolean): MatMulFlags = {
    (new MatMulFlags).Lit(
      _._unused    -> 0.U,
      _.zeroes     -> zeroes.B,
      _.accumulate -> accumulate.B
    )
  }

  def isValid(flags: UInt): Bool = flags < 4.U
}
