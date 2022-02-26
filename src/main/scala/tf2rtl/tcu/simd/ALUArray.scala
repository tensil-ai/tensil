package tf2rtl.tcu.simd

import chisel3._
import chisel3.util.{Decoupled, Queue}
import tf2rtl.util.{Delay, zero}
import tf2rtl.util.decoupled.QueueWithReporting
import tf2rtl.tools.compiler.InstructionLayout
import tf2rtl.Architecture
import tf2rtl.tcu.simd

class ALUArray[T <: Data with Num[T]](
    gen: T,
    arch: Architecture,
) extends Module {
  val layout          = InstructionLayout(arch)
  val instructionType = new Instruction(layout)
  val width           = arch.arraySize
  val numOps          = simd.Op.numOps
  val numRegisters    = arch.simdRegistersDepth

  val io = IO(new Bundle {
    val input       = Flipped(Decoupled(Vec(width, gen)))
    val output      = Decoupled(Vec(width, gen))
    val instruction = Flipped(Decoupled(instructionType))
  })

  val input       = io.input
  val instruction = io.instruction
  // val input       = QueueWithReporting(io.input, 2)
  // val instruction = QueueWithReporting(io.instruction, 2)
  val output = Module(new Queue(chiselTypeOf(io.output.bits), 2, flow = true))
  io.output <> output.io.deq

  val inputNotNeeded =
    instruction.bits.op === Op.NoOp.U || instruction.bits.op === Op.Zero.U ||
      (Op.isUnary(
        instruction.bits.op
      ) && instruction.bits.sourceLeft =/= 0.U) ||
      (instruction.bits.sourceLeft =/= 0.U && instruction.bits.sourceRight =/= 0.U)
  val inputNeeded = !inputNotNeeded

  input.ready := output.io.enq.ready && instruction.valid && inputNeeded
  instruction.ready := ((input.valid || !inputNeeded) && output.io.enq.ready)
  output.io.enq.valid := Delay(
    (input.valid || !inputNeeded) && instruction.valid,
    2 // one cycle for input register, one cycle for output register
  )

  val alu = for (i <- 0 until width) yield {
    val m = Module(
      new ALU(
        gen,
        numOps,
        numRegisters,
        inputRegisters = true,
        outputRegister = true
      )
    )
    m.io.op := Mux(
      instruction.ready && instruction.valid,
      instruction.bits.op,
      Op.NoOp.U
    )
    m.io.sourceLeft := instruction.bits.sourceLeft
    m.io.sourceRight := instruction.bits.sourceRight
    m.io.dest := instruction.bits.dest
    m.io.input := input.bits(i)
    output.io.enq.bits(i) := m.io.output
  }
}
