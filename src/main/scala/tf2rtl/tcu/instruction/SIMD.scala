package tf2rtl.tcu.instruction

import chisel3._
import chisel3.experimental.BundleLiterals._
import tf2rtl.tools.compiler.InstructionLayout
import tf2rtl.tcu.simd

class SIMDArgs(
    val layout: InstructionLayout
) extends Bundle {
  private val paddingWidth =
    layout.operand2SizeBits - layout.simdInstructionSizeBits
  val _unused         = UInt(paddingWidth.W)
  val instruction     = new simd.Instruction(layout)
  val accReadAddress  = UInt(layout.operand1SizeBits.W)
  val accWriteAddress = UInt(layout.operand0SizeBits.W)
}

object SIMDArgs {
  // val numOps       = simd.Instruction.numOps
  // val numRegisters = simd.Instruction.numRegisters

  def apply(
      accReadAddress: BigInt,
      accWriteAddress: BigInt,
      instruction: simd.Instruction
  )(implicit layout: InstructionLayout): SIMDArgs = {
    new SIMDArgs(layout).Lit(
      _.accReadAddress  -> accReadAddress.U,
      _.accWriteAddress -> accWriteAddress.U,
      _.instruction     -> instruction,
      _._unused         -> 0.U
    )
  }
}

class SIMDFlags extends Bundle {
  val _unused = UInt(1.W)
  val accumulate =
    Bool() // whether to accumulate (true) or overwrite when writing
  val write = Bool() // whether to write to the given accumulator address
  val read  = Bool() // whether to read from the given accumulator address
}

object SIMDFlags {
  def apply(read: Boolean, write: Boolean, accumulate: Boolean): SIMDFlags = {
    new SIMDFlags().Lit(
      _._unused    -> 0.U,
      _.read       -> read.B,
      _.write      -> write.B,
      _.accumulate -> accumulate.B
    )
  }

  def isValid(flags: UInt): Bool = flags < 8.U
}
