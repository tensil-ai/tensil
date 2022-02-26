package tf2rtl.tcu.instruction

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util.log2Ceil
import tf2rtl.tools.compiler.InstructionLayout

class ConfigureArgs(val registerWidth: Int, val argumentsWidth: Int)
    extends Bundle {
  val value    = UInt((argumentsWidth - registerWidth).W)
  val register = UInt(registerWidth.W)
}

object ConfigureArgs {
  val registerWidth = Box(4)

  def apply(register: Int, value: Int)(implicit
      layout: InstructionLayout
  ): ConfigureArgs =
    new ConfigureArgs(registerWidth, layout.operandsSizeBits)
      .Lit(_.register -> register.U, _.value -> value.U)

  def apply(register: UInt, value: Int)(implicit
      layout: InstructionLayout
  ): ConfigureArgs =
    new ConfigureArgs(registerWidth, layout.operandsSizeBits)
      .Lit(_.register -> register, _.value -> value.U)
}

object Configure {
  val dram0AddressOffset  = 0x00.U
  val dram0CacheBehaviour = 0x01.U
  // unused 0x02-0x03
  val dram1AddressOffset  = 0x04.U
  val dram1CacheBehaviour = 0x05.U
  // unused 0x06-0x07
  val timeout        = 0x08.U
  val tracepoint     = 0x09.U
  val programCounter = 0x0a.U
  val sampleInterval = 0x0b.U
}
