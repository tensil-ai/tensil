/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.experimental.BundleLiterals._
import chisel3.util.Decoupled
import chiseltest._
import tensil.util.decoupled.Counter
import chisel3.util.Queue
import chisel3.util.DecoupledIO

class Scratch(depth: Int) extends Module {
  val io = IO(new Bundle {
    val a = new Bundle {
      val in  = Flipped(Decoupled(new Request(depth)))
      val out = Decoupled(new Request(depth))
    }
    val b = new Bundle {
      val in  = Flipped(Decoupled(new Request(depth)))
      val out = Decoupled(new Request(depth))
    }
  })

  val a = Queue(io.a.in, 2)
  val b = Queue(io.b.in, 2)

  // when a barrier comes in, the port has to wait until the other port also
  // receives a barrier
  // barriers are transmitted onwards, can contain a request too

  when(a.valid && a.bits.barrier) {
    when(b.valid && b.bits.barrier) {
      proceed()
    }.otherwise {
      // hold A
      a.ready := false.B
      io.a.out.valid := false.B
      io.a.out.bits := DontCare
      io.b.out <> b
    }
  }.otherwise {
    when(b.valid && b.bits.barrier) {
      // hold B
      b.ready := false.B
      io.b.out.valid := false.B
      io.b.out.bits := DontCare
      io.a.out <> a
    }.otherwise {
      proceed()
    }
  }

  def proceed(): Unit = {
    io.a.out <> a
    io.b.out <> b
  }
}

class ScratchSpec extends FunUnitSpec {
  describe("Scratch") {
    describe("when depth = 1 << 8") {
      val depth = 8
      it("should allow requests to proceed when there are no barriers") {
        decoupledTest(new Scratch(depth)) { m =>
          m.io.a.in.setSourceClock(m.clock)
          m.io.a.out.setSinkClock(m.clock)
          m.io.b.in.setSourceClock(m.clock)
          m.io.b.out.setSinkClock(m.clock)

          thread("a.in") {
            m.io.a.in.enqueue(Request(depth, 0, false, false))
          }

          thread("b.in") {
            m.io.b.in.enqueue(Request(depth, 0, false, false))
          }

          thread("a.out") {
            m.io.a.out.expectDequeue(Request(depth, 0, false, false))
          }

          thread("b.out") {
            m.io.b.out.expectDequeue(Request(depth, 0, false, false))
          }
        }
      }

      it("should block requests until barrier arrives on both ports") {
        decoupledTest(new Scratch(depth)) { m =>
          m.io.a.in.setSourceClock(m.clock)
          m.io.a.out.setSinkClock(m.clock)
          m.io.b.in.setSourceClock(m.clock)
          m.io.b.out.setSinkClock(m.clock)

          val delay = 10

          thread("a.in") {
            m.io.a.in.enqueue(Request(depth, 0, false, true))
          }

          thread("b.in") {
            m.clock.step(delay)
            m.io.b.in.enqueue(Request(depth, 0, false, true))
          }

          thread("a.out") {
            for (i <- 0 until delay) {
              m.io.a.out.valid.expect(false.B)
              m.clock.step()
            }
            m.io.a.out.expectDequeue(Request(depth, 0, false, true))
          }

          thread("b.out") {
            m.io.b.out.expectDequeue(Request(depth, 0, false, true))
          }
        }
      }

      it(
        "should allow requests on b while a is blocked until barrier arrives on b"
      ) {
        decoupledTest(new Scratch(depth)) { m =>
          m.io.a.in.setSourceClock(m.clock)
          m.io.a.out.setSinkClock(m.clock)
          m.io.b.in.setSourceClock(m.clock)
          m.io.b.out.setSinkClock(m.clock)

          thread("a.in") {
            m.io.a.in.enqueue(Request(depth, 0, false, true))
          }

          thread("b.in") {
            m.io.b.in.enqueue(Request(depth, 0, false, false))
            m.io.b.in.enqueue(Request(depth, 0, false, true))
          }

          thread("a.out") {
            m.io.a.out.expectDequeue(Request(depth, 0, false, true))
          }

          thread("b.out") {
            m.io.b.out.expectDequeue(Request(depth, 0, false, false))
            m.io.b.out.expectDequeue(Request(depth, 0, false, true))
          }
        }
      }
    }
  }
}

class Request(val depth: Long) extends Bundle {
  val address = UInt(log2Ceil(depth).W)
  val write   = Bool()
  val barrier = Bool()
}

object Request {
  def apply(
      depth: Long,
      address: Int,
      write: Boolean,
      barrier: Boolean
  ): Request = {
    (new Request(depth)).Lit(
      _.address -> address.U,
      _.write   -> write.B,
      _.barrier -> barrier.B
    )
  }
}
