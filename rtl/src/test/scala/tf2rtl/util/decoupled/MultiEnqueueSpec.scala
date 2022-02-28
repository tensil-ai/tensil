package tensil.util.decoupled

import chisel3._
import chiseltest._
import tensil.FunUnitSpec
import chisel3.util.Decoupled
import chisel3.util.Queue

class MultiEnqueueSpec extends FunUnitSpec {
  describe("MultiEnqueue") {
    describe("when n = 2") {
      val n = 2

      it("should enqueue to out(0) even though out(1) is not ready") {
        test(new MultiEnqueue(n)) { m =>
          m.io.out(0).ready.poke(true.B)
          m.io.out(1).ready.poke(false.B)

          m.clock.step(1)

          m.io.in.ready.expect(false.B)

          m.io.in.valid.poke(true.B)

          m.io.out(0).valid.expect(true.B)
          m.io.out(1).valid.expect(true.B)
          m.io.in.ready.expect(false.B)

          m.clock.step(1)

          m.io.out(0).valid.expect(false.B)
          m.io.out(1).valid.expect(true.B)
          m.io.in.ready.expect(false.B)

          m.clock.step(1)

          m.io.out(1).ready.poke(true.B)

          m.io.out(0).valid.expect(false.B)
          m.io.out(1).valid.expect(true.B)
          m.io.in.ready.expect(true.B)

          m.clock.step(1)

          m.io.out(0).ready.poke(false.B)
          m.io.out(1).ready.poke(false.B)

          m.io.in.valid.poke(false.B)

          m.io.out(0).valid.expect(false.B)
          m.io.out(1).valid.expect(false.B)
        }
      }

      it("should work in the context of a decoupled IO module") {
        class MultiEnqueueTest extends Module {
          val io = IO(new Bundle {
            val in  = Flipped(Decoupled(Bool()))
            val out = Vec(2, Decoupled(Bool()))
          })

          val in = io.in

          val mux = Module(new MultiEnqueue(2))
          mux.io.in <> ReadyValid(in)
          io.out(0) <> mux.io.out(0).toDecoupled(in.bits)
          io.out(1) <> mux.io.out(1).toDecoupled(!in.bits)
        }
        test(new MultiEnqueueTest) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out(0).setSinkClock(m.clock)
          m.io.out(1).setSinkClock(m.clock)

          def randInt(): Int = Math.round(Math.random() * 10).toInt

          val numPackets = 10

          val t0 = fork {
            for (i <- 0 until numPackets) {
              m.clock.step(randInt)
              m.io.in.enqueue(false.B)
            }
          }

          val t1 = fork {
            for (i <- 0 until numPackets) {
              m.clock.step(randInt)
              m.io.out(0).expectDequeue(false.B)
            }
          }

          val t2 = fork {
            for (i <- 0 until numPackets) {
              m.clock.step(randInt)
              m.io.out(1).expectDequeue(true.B)
            }
          }

          t0.join()
          t1.join()
          t2.join()
        }
      }
    }
  }
}
