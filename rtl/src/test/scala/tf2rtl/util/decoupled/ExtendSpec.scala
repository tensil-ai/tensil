package tensil.util.decoupled

import chisel3._
import chisel3.experimental.FixedPoint
import chiseltest._
import tensil.FunUnitSpec

class ExtendSpec extends FunUnitSpec {
  describe("Extend") {
    describe("when in = FP(18.W, 8.BP) and out = FP(32.W, 8.BP)") {
      val gen     = FixedPoint(18.W, 8.BP)
      val desired = FixedPoint(32.W, 8.BP)
      it("should work") {
        test(new Extend(gen, desired)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          val t = fork {
            m.io.out.expectDequeue(123.F(32.W, 8.BP))
          }

          m.io.in.enqueue(123.F(18.W, 8.BP))

          t.join()
        }
      }
    }

    describe("when in = FP(18.W, 8.BP) and out = FP(32.W, 16.BP)") {
      val gen     = FixedPoint(18.W, 8.BP)
      val desired = FixedPoint(32.W, 16.BP)
      it("should work") {
        test(new Extend(gen, desired)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          val t = fork {
            m.io.out.expectDequeue(123.F(32.W, 16.BP))
          }

          m.io.in.enqueue(123.F(18.W, 8.BP))

          t.join()
        }
      }
    }

    describe("when in = FP(32.W, 16.BP) and out = FP(18.W, 10.BP)") {
      val gen     = FixedPoint(32.W, 16.BP)
      val desired = FixedPoint(18.W, 10.BP)
      it("should work") {
        test(new Extend(gen, desired)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          val t = fork {
            m.io.out.expectDequeue(123.F(18.W, 10.BP))
          }

          m.io.in.enqueue(123.F(32.W, 16.BP))

          t.join()
        }
      }
    }
  }
}
