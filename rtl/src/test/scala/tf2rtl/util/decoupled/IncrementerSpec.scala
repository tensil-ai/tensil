package tensil.util.decoupled

import chisel3._
import chiseltest._
import tensil.FunUnitSpec

import scala.util.Random

class IncrementerSpec extends FunUnitSpec {
  describe("DecoupledIncrementer") {
    describe("when width = 32") {
      it("should increment values by 1") {
        test(new Incrementer(32)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          for (_ <- 0 until 50) {
            val x = Random.nextInt(Int.MaxValue)
            m.io.in.enqueue(x.U)
            m.io.out.expectDequeue((x + 1).U)
          }
        }
      }
    }
  }
}
