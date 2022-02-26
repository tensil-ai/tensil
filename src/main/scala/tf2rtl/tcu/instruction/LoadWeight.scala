package tf2rtl.tcu.instruction

import chisel3._
import chisel3.experimental.BundleLiterals._
import tf2rtl.tools.compiler.InstructionLayout

class LoadWeightArgs(
    val layout: InstructionLayout,
) extends Bundle {
  val size    = UInt(layout.operand1SizeBits.W)
  val _unused = UInt(layout.operand0Padding.W)
  val stride  = UInt(layout.stride0SizeBits.W)
  val address = UInt(layout.operand0AddressSizeBits.W)
}

object LoadWeightArgs {
  def apply(address: BigInt)(implicit
      layout: InstructionLayout
  ): LoadWeightArgs = apply(address, 0)

  def apply(address: BigInt, size: BigInt)(implicit
      layout: InstructionLayout
  ): LoadWeightArgs = apply(address, size, 0)

  def apply(address: BigInt, size: BigInt, stride: Int)(implicit
      layout: InstructionLayout
  ): LoadWeightArgs = {
    new LoadWeightArgs(layout)
      .Lit(
        _.address -> address.U,
        _.size    -> size.U,
        _.stride  -> stride.U,
        _._unused -> 0.U
      )
  }
}

class LoadWeightFlags extends Bundle {
  val _unused = UInt(3.W)
  val zeroes  = Bool()
}

object LoadWeightFlags extends Bundle {
  def apply(zeroes: Boolean): LoadWeightFlags = {
    (new LoadWeightFlags).Lit(_._unused -> 0.U, _.zeroes -> zeroes.B)
  }

  def isValid(flags: UInt): Bool = flags < 2.U
}
