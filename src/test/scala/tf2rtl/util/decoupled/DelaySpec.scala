package tf2rtl.util.decoupled

import chisel3._
import chiseltest._
import tf2rtl.UnitSpec
import tf2rtl.decoupled.decoupledToDriver

class DelaySpec extends UnitSpec {
  behavior of "DecoupledDelay"

  it should "hold the value of the last data transferred" in {
    test(new Delay(UInt(8.W), 1)) { m =>
      m.io.in.setSourceClock(m.clock)
      m.io.out.setSinkClock(m.clock)

      fork {
        m.io.in.enqueue(123.U)
      }
      m.io.out.expectDequeue(123.U)
      m.io.delayed.expect(123.U)
    }
  }

  it should "work with n = 2 cycles" in {
    test(new Delay(UInt(8.W), 2)) { m =>
      m.io.in.setSourceClock(m.clock)
      m.io.out.setSinkClock(m.clock)

      fork {
        m.io.in.enqueue(123.U)
        m.io.in.enqueue(231.U)
      }
      m.io.out.expectDequeue(123.U)
      m.io.out.expectDequeue(231.U)
      m.io.delayed.expect(123.U)
    }
  }

  it should "work with n = 10 cycles" in {
    test(new Delay(UInt(8.W), 10)) { m =>
      m.io.in.setSourceClock(m.clock)
      m.io.out.setSinkClock(m.clock)

      fork {
        for (i <- 1 to 20) {
          m.io.in.enqueue(i.U)
        }
      }
      for (i <- 1 to 20) {
        m.io.out.expectDequeue(i.U)
        if (i > 10) {
          m.io.delayed.expect((i - 9).U)
        }
      }
    }
  }
}
