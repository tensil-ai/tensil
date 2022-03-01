package tensil.tcu.simd

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util.log2Ceil
import tensil.tcu.instruction.Box
import tensil.InstructionLayout

class Instruction(
    val layout: InstructionLayout
) extends Bundle {
  val op = UInt(layout.simdOpSizeBits.W)
  val sourceLeft =
    UInt(layout.simdOperandSizeBits.W) // 0 = io.input, 1 = register 0, ...
  val sourceRight = UInt(layout.simdOperandSizeBits.W)
  val dest        = UInt(layout.simdOperandSizeBits.W)
}

object Instruction {
  def apply(
      op: BigInt,
      sourceLeft: BigInt,
      sourceRight: BigInt,
      dest: BigInt,
  )(implicit layout: InstructionLayout): Instruction = {
    new Instruction(layout).Lit(
      _.op          -> op.U,
      _.sourceLeft  -> sourceLeft.U,
      _.sourceRight -> sourceRight.U,
      _.dest        -> dest.U
    )
  }

  def noOp()(implicit layout: InstructionLayout): Instruction = {
    Instruction(Op.NoOp, 0, 0, 0)
  }
}
