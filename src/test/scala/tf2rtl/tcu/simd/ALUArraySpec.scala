package tf2rtl.tcu.simd

import chisel3._
import chiseltest._
import tf2rtl.UnitSpec
import tf2rtl.decoupled.{decoupledToDriver, decoupledVecToDriver}

import scala.reflect.ClassTag
import tf2rtl.Architecture
import tf2rtl.tools.compiler.InstructionLayout

class ALUArraySpec extends UnitSpec {
  behavior of "ALUArray"

  implicit class ALUArrayHelper[T <: Data with Num[T] : ClassTag](
      m: ALUArray[T]
  ) {
    def setupClocks(): Unit = {
      m.io.input.setSourceClock(m.clock)
      m.io.instruction.setSourceClock(m.clock)
      m.io.output.setSinkClock(m.clock)
    }
  }

  it should "relu" in {
    val gen          = SInt(16.W)
    val width        = 2
    val numOps       = Op.numOps
    val numRegisters = 1
    val arch = Architecture.mkWithDefaults(
      arraySize = width,
      simdRegistersDepth = numRegisters,
    )
    implicit val layout = InstructionLayout(arch)

    decoupledTest(new ALUArray(gen, arch)) { m =>
      m.setupClocks()

      // zero the register (dest = 1 meaning the first register)
      thread("instruction") {
        m.io.instruction.enqueue(Instruction(Op.Zero, 0, 0, 1))
      }
      thread("output") {
        m.io.output.expectDequeue(Array(0.S, 0.S))
      }

      // queue up data
      thread("input") {
        m.io.input.enqueue(Array(12.S, -34.S))
      }

      // max the input data with the zero in the register to compute a ReLU
      // do not store the result in the register (dest = 0 meaning output only)
      thread("instruction") {
        m.io.instruction.enqueue(Instruction(Op.Max, 0, 1, 0))
      }

      thread("output") {
        m.io.output.expectDequeue(Array(12.S, 0.S))
      }
    }
  }

  it should "max" in {
    val gen          = SInt(16.W)
    val width        = 2
    val numOps       = Op.numOps
    val numRegisters = 1
    val arch = Architecture.mkWithDefaults(
      arraySize = width,
      simdRegistersDepth = numRegisters,
    )
    implicit val layout = InstructionLayout(arch)

    decoupledTest(new ALUArray(gen, arch)) { m =>
      m.setupClocks()

      // queue up data
      thread("input") {
        m.io.input.enqueue(Array(12.S, -34.S))
      }
      // max the input data with itself (since the data in the register is not
      // valid yet). Store the result in the firt register (dest = 1)
      thread("instruction") {
        m.io.instruction.enqueue(Instruction(Op.Max, 0, 0, 1))
      }
      thread("output") {
        m.io.output.expectDequeue(Array(12.S, -34.S))
      }

      // max the input data with the contents of the first register, and store
      // the result in the first register
      thread("input") {
        m.io.input.enqueue(Array(13.S, -33.S))
      }
      thread("instruction") {
        m.io.instruction.enqueue(Instruction(Op.Max, 0, 1, 1))
      }
      thread("output") {
        m.io.output.expectDequeue(Array(13.S, -33.S))
      }

      thread("input") {
        m.io.input.enqueue(Array(11.S, -35.S))
      }
      thread("instruction") {
        m.io.instruction.enqueue(Instruction(Op.Max, 0, 1, 1))
      }
      thread("output") {
        m.io.output.expectDequeue(Array(13.S, -33.S))
      }

      thread("input") {
        m.io.input.enqueue(Array(-35.S, 11.S))
      }
      thread("instruction") {
        m.io.instruction.enqueue(Instruction(Op.Max, 0, 1, 1))
      }
      thread("output") {
        m.io.output.expectDequeue(Array(13.S, 11.S))
      }
    }
  }
}
