package tensil.tcu.simd

import chisel3._
import chisel3.experimental.FixedPoint
import chiseltest._
import tensil.UnitSpec

class ALUSpec extends UnitSpec {
  behavior of "ALU"

  it should "multiply negative fixed points correctly" in {
    val gen          = FixedPoint(16.W, 8.BP)
    val numRegisters = 1
    val numOps       = Op.numOps
    test(new ALU(gen, numOps, numRegisters)) { m =>
      m.io.op.poke(Op.Move.U)
      m.io.sourceLeft.poke(0.U)
      m.io.dest.poke(1.U)
      m.io.input.poke(-0.1.F(8.BP))

      m.clock.step()

      m.io.op.poke(Op.Multiply.U)
      m.io.sourceLeft.poke(1.U)
      m.io.sourceRight.poke(0.U)
      m.io.dest.poke(0.U)

      m.io.input.poke(0.265625.F(8.BP))
      m.io.output.expect(-0.02734375.F(8.BP))
    }
  }

  it should "relu" in {
    val gen          = SInt(8.W)
    val numRegisters = 1
    val numOps       = Op.numOps
    test(new ALU(gen, numOps, numRegisters)) { m =>
      m.io.op.poke(Op.Zero.U)
      m.io.dest.poke(1.U)

      m.clock.step()

      m.io.op.poke(Op.Max.U)
      m.io.sourceLeft.poke(0.U)
      m.io.sourceRight.poke(1.U)
      m.io.dest.poke(0.U)

      m.io.input.poke(1.S)
      m.io.output.expect(1.S)

      m.io.input.poke(-1.S)
      m.io.output.expect(0.S)
    }
  }

  it should "max" in {
    val gen          = SInt(8.W)
    val numRegisters = 1
    val numOps       = Op.numOps
    test(new ALU(gen, numOps, numRegisters)) { m =>
      m.io.op.poke(Op.Max.U)
      m.io.sourceLeft.poke(0.U)
      m.io.sourceRight.poke(
        0.U
      ) // initially the state is invalid so we max the value with itself
      m.io.dest.poke(1.U)

      m.io.input.poke(1.S)
      m.io.output.expect(1.S)

      m.clock.step()

      m.io.sourceRight.poke(1.U)

      m.io.input.poke(-1.S)
      m.io.output.expect(1.S)

      m.clock.step()

      m.io.input.poke(2.S)
      m.io.output.expect(2.S)

      m.clock.step()

      m.io.input.poke(1.S)
      m.io.output.expect(2.S)
    }
  }

  it should "move data into a register and then read it back out" in {
    val gen          = SInt(8.W)
    val numRegisters = 1
    val numOps       = Op.numOps
    test(new ALU(gen, numOps, numRegisters)) { m =>
      m.io.op.poke(Op.Move.U)
      m.io.sourceLeft.poke(0.U)
      m.io.dest.poke(1.U)

      m.io.input.poke(123.S)

      m.clock.step()

      m.io.sourceLeft.poke(1.U)
      m.io.dest.poke(0.U)

      m.io.output.expect(123.S)
    }
  }

  it should "zero" in {
    val gen          = SInt(8.W)
    val numRegisters = 1
    val numOps       = Op.numOps
    test(new ALU(gen, numOps, numRegisters)) { m =>
      m.io.op.poke(Op.Move.U)
      m.io.sourceLeft.poke(0.U)
      m.io.dest.poke(1.U)

      m.io.input.poke(123.S)

      m.clock.step()

      m.io.op.poke(Op.Zero.U)
      m.io.dest.poke(1.U)

      m.clock.step()

      m.io.op.poke(Op.Move.U)
      m.io.sourceLeft.poke(1.U)
      m.io.dest.poke(0.U)

      m.io.output.expect(0.S)
    }
  }

  it should "noop" in {
    val gen          = SInt(8.W)
    val numRegisters = 1
    val numOps       = Op.numOps
    test(new ALU(gen, numOps, numRegisters)) { m =>
      m.io.op.poke(Op.NoOp.U)
      m.io.sourceLeft.poke(0.U)
      m.io.dest.poke(1.U)
      m.io.input.poke(123.S)

      m.clock.step()

      m.io.op.poke(Op.Move.U)
      m.io.sourceLeft.poke(1.U)
      m.io.dest.poke(0.U)

      m.io.output.expect(0.S)
    }
  }

  it should "work with input registers" in {
    val gen          = SInt(8.W)
    val numRegisters = 1
    val numOps       = Op.numOps
    test(new ALU(gen, numOps, numRegisters, inputRegisters = true)) { m =>
      m.io.op.poke(Op.Zero.U)
      m.io.dest.poke(1.U)

      m.clock.step()

      m.io.op.poke(Op.Max.U)
      m.io.sourceLeft.poke(0.U)
      m.io.sourceRight.poke(1.U)
      m.io.dest.poke(0.U)

      m.io.input.poke(1.S)
      m.clock.step()
      m.io.output.expect(1.S)

      m.io.input.poke(-1.S)
      m.clock.step()
      m.io.output.expect(0.S)
    }
  }
}
