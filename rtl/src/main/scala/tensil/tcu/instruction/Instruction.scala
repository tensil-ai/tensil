package tensil.tcu.instruction

import chisel3._
import chisel3.experimental.BundleLiterals._
import tensil.InstructionLayout

class Instruction(val instructionWidth: Int) extends Bundle {
  val opcode    = UInt(4.W)
  val flags     = UInt(4.W)
  val arguments = UInt((instructionWidth - 8).W)
}

object Instruction {
  def apply(opcode: UInt, flags: Bundle, arguments: Bundle)(implicit
      layout: InstructionLayout
  ): Instruction = {
    new Instruction(layout.instructionSizeBytes * 8).Lit(
      _.opcode    -> opcode,
      _.flags     -> flags.litValue.U,
      _.arguments -> arguments.litValue.U
    )
  }

  def apply(opcode: UInt, arguments: Bundle)(implicit
      layout: InstructionLayout
  ): Instruction = {
    new Instruction(layout.instructionSizeBytes * 8).Lit(
      _.opcode    -> opcode,
      _.flags     -> 0.U,
      _.arguments -> arguments.litValue.U
    )
  }

  def apply(opcode: UInt)(implicit
      layout: InstructionLayout
  ): Instruction = {
    new Instruction(layout.instructionSizeBytes * 8)
      .Lit(_.opcode -> opcode, _.flags -> 0.U, _.arguments -> 0.U)
  }

  def fromUInt(u: UInt)(implicit layout: InstructionLayout): Instruction = {
    val width     = layout.instructionSizeBytes * 8
    val opcode    = u(width - 1, width - 4)
    val flags     = u(width - 5, width - 8)
    val arguments = u(width - 9, 0)
    new Instruction(width).Lit(
      _.opcode    -> opcode,
      _.flags     -> flags,
      _.arguments -> arguments,
    )
  }
}
