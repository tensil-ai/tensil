package tensil.util.decoupled

import chisel3._
import chiseltest._
import tensil.FunUnitSpec
import scala.collection.mutable
import chiseltest.internal.TesterThreadList

class MuxSpec extends FunUnitSpec {
  describe("DecoupledMux") {
    it("should multiplex inputs") {
      decoupledTest(new Mux(UInt(8.W), 2)) { m =>
        m.io.in(0).setSourceClock(m.clock)
        m.io.in(1).setSourceClock(m.clock)
        m.io.sel.setSourceClock(m.clock)
        m.io.out.setSinkClock(m.clock)

        thread("in0") {
          m.io.in(0).enqueue(3.U)
        }
        thread("in1") {
          m.io.in(1).enqueue(7.U)
        }
        thread("sel") {
          m.io.sel.enqueue(1.U)
          m.io.sel.enqueue(0.U)
        }
        thread("out") {
          m.io.out.expectDequeue(7.U)
          m.io.out.expectDequeue(3.U)
        }
      }
    }
  }
}
