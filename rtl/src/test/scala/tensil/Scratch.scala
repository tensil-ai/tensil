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

  val holdAReg = RegInit(false.B)
  val holdBReg = RegInit(false.B)

  def hold(reg: Bool, bus: DecoupledIO[Request]): Bool =
    (reg && !(bus.valid && bus.bits.signal === Signal.Release.U)) || (bus.valid && bus.bits.signal === Signal.Hold.U)

  val holdA = hold(holdAReg, b)
  val holdB = hold(holdBReg, a)

  when(a.valid) {
    when(a.bits.signal === Signal.Hold.U) {
      holdBReg := true.B
    }.elsewhen(a.bits.signal === Signal.Release.U) {
      holdBReg := false.B
    }
  }

  when(b.valid) {
    when(b.bits.signal === Signal.Hold.U) {
      holdAReg := true.B
    }.elsewhen(b.bits.signal === Signal.Release.U) {
      holdAReg := false.B
    }
  }

  when(holdA) {
    when(holdB) {
      // both holds requested, break race condition by preferring to hold B
      io.a.out <> a
      b.ready := false.B
      io.b.out.valid := false.B
      io.b.out.bits := DontCare
    }.otherwise {
      // hold A
      a.ready := false.B
      io.a.out.valid := false.B
      io.a.out.bits := DontCare
      io.b.out <> b
    }
  }.otherwise {
    when(holdB) {
      // hold B
      io.a.out <> a
      b.ready := false.B
      io.b.out.valid := false.B
      io.b.out.bits := DontCare
    }.otherwise {
      io.a.out <> a
      io.b.out <> b
    }
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
            m.io.a.in.enqueue(Request(depth, 0, false, Signal.None))
          }

          thread("b.in") {
            m.io.b.in.enqueue(Request(depth, 0, false, Signal.None))
          }

          thread("a.out") {
            m.io.a.out.expectDequeue(Request(depth, 0, false, Signal.None))
          }

          thread("b.out") {
            m.io.b.out.expectDequeue(Request(depth, 0, false, Signal.None))
          }
        }
      }

      it("should hold requests on B until release arrives on A") {
        decoupledTest(new Scratch(depth)) { m =>
          m.io.a.in.setSourceClock(m.clock)
          m.io.a.out.setSinkClock(m.clock)
          m.io.b.in.setSourceClock(m.clock)
          m.io.b.out.setSinkClock(m.clock)

          val delay = 10

          thread("a.in") {
            m.io.a.in.enqueue(Request(depth, 0, false, Signal.Hold))
            for (_ <- 0 until delay) {
              m.clock.step()
            }
            m.io.a.in.enqueue(Request(depth, 0, false, Signal.Release))
          }

          thread("b.in") {
            m.io.b.in.enqueue(Request(depth, 0, false, Signal.None))
          }

          thread("a.out") {
            m.io.a.out.expectDequeue(Request(depth, 0, false, Signal.Hold))
            m.io.a.out.expectDequeue(Request(depth, 0, false, Signal.Release))
          }

          thread("b.out") {
            for (_ <- 0 until delay) {
              m.io.b.out.valid.expect(false.B)
              m.clock.step()
            }
            m.io.b.out.expectDequeue(Request(depth, 0, false, Signal.None))
          }
        }
      }

      it("should hold requests on A until release arrives on B") {
        decoupledTest(new Scratch(depth)) { m =>
          m.io.a.in.setSourceClock(m.clock)
          m.io.a.out.setSinkClock(m.clock)
          m.io.b.in.setSourceClock(m.clock)
          m.io.b.out.setSinkClock(m.clock)

          val delay = 10

          thread("a.in") {
            m.io.a.in.enqueue(Request(depth, 0, false, Signal.None))
          }

          thread("b.in") {
            m.io.b.in.enqueue(Request(depth, 0, false, Signal.Hold))
            for (_ <- 0 until delay) {
              m.clock.step()
            }
            m.io.b.in.enqueue(Request(depth, 0, false, Signal.Release))
          }

          thread("a.out") {
            for (_ <- 0 until delay) {
              m.io.a.out.valid.expect(false.B)
              m.clock.step()
            }
            m.io.a.out.expectDequeue(Request(depth, 0, false, Signal.None))
          }

          thread("b.out") {
            m.io.b.out.expectDequeue(Request(depth, 0, false, Signal.Hold))
            m.io.b.out.expectDequeue(Request(depth, 0, false, Signal.Release))
          }
        }
      }

      it(
        "should prefer to block B when holds are requested on both ports"
      ) {
        decoupledTest(new Scratch(depth)) { m =>
          m.io.a.in.setSourceClock(m.clock)
          m.io.a.out.setSinkClock(m.clock)
          m.io.b.in.setSourceClock(m.clock)
          m.io.b.out.setSinkClock(m.clock)

          thread("a.in") {
            m.io.a.in.enqueue(Request(depth, 0, false, Signal.Hold))
            m.io.a.in.enqueue(Request(depth, 0, false, Signal.None))
          }

          thread("b.in") {
            m.io.b.in.enqueue(Request(depth, 0, false, Signal.Hold))
          }

          thread("a.out") {
            m.io.a.out.expectDequeue(Request(depth, 0, false, Signal.Hold))
            m.io.a.out.expectDequeue(Request(depth, 0, false, Signal.None))
          }

          thread("b.out") {
            for (_ <- 0 until 5) {
              m.io.b.out.valid.expect(false.B)
            }
          }
        }
      }
    }
  }
}

class Request(val depth: Long) extends Bundle {
  val address = UInt(log2Ceil(depth).W)
  val write   = Bool()
  val signal  = UInt(2.W)
}

object Request {
  def apply(
      depth: Long,
      address: Int,
      write: Boolean,
      signal: Int,
  ): Request = {
    (new Request(depth)).Lit(
      _.address -> address.U,
      _.write   -> write.B,
      _.signal  -> signal.U
    )
  }
}

object Signal {
  val None    = 0x0
  val Hold    = 0x1
  val Release = 0x2
  val _unused = 0x3
}
