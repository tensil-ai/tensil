package tensil.tcu

import chisel3._
import chisel3.experimental.FixedPoint
import chiseltest._
import tensil.UnitSpec

class MACSpec extends UnitSpec {
  behavior of "MAC"

  it should "compute a multiply-add" in {
    test(new MAC(UInt(8.W))) { m =>
      m.io.load.poke(true.B)
      m.io.addInput.poke(1.U)
      m.clock.step()
      m.io.load.poke(false.B)
      m.io.mulInput.poke(1.U)
      m.io.addInput.poke(1.U)
      m.clock.step()
      m.io.output.expect(2.U)
      m.io.passthrough.expect(1.U)
    }
  }

  it should "compute fixed point multiplication of negatives correctly" in {
    test(new MAC(FixedPoint(16.W, 8.BP))) { m =>
      m.io.load.poke(true.B)
      m.io.addInput.poke(-0.1.F(8.BP))
      m.clock.step()
      m.io.load.poke(false.B)
      m.io.mulInput.poke(0.265625.F(8.BP))
      m.io.addInput.poke(0.F(8.BP))
      m.clock.step()
      m.io.output.expect(-0.02734375.F(8.BP))
    }
  }
}
