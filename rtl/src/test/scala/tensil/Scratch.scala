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

class Scratch extends Module {
  val gen               = UInt(8.W)
  val depth             = 1 << 8
  val blockSizeShift    = 4
  val blockSize         = 1 << blockSizeShift
  val maxRequestCount   = 1 << 8
  val controlBufferSize = 8
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

  // new request on a comes in, what do we do?
  val tableSize = depth / blockSize
  val aTable    = Module(new CounterTable(tableSize, maxRequestCount))
  val bTable    = Module(new CounterTable(tableSize, maxRequestCount))

  val a = io.a
  val b = io.b

  val aQueue = Queue(a.in, controlBufferSize)
  val bQueue = Queue(b.in, controlBufferSize)

  val bSafe = connectTable(aTable, a.in, aQueue, bQueue)
  val aSafe = connectTable(bTable, b.in, bQueue, aQueue)

  when(aSafe) {
    when(bSafe) {
      // both can proceed
      a.out <> aQueue
      b.out <> bQueue
    }.otherwise {
      // a can proceed, b cannot
      a.out <> aQueue
      bQueue.ready := false.B
      b.out.valid := false.B
      b.out.bits <> bQueue.bits
    }
  }.otherwise {
    when(bSafe) {
      // b can proceed, a cannot
      aQueue.ready := false.B
      a.out.valid := false.B
      a.out.bits <> aQueue.bits
      b.out <> bQueue
    }.otherwise {
      // both unsafe to proceed, a gets priority to ensure progress is made
      a.out <> aQueue
      bQueue.ready := false.B
      b.out.valid := false.B
      b.out.bits <> bQueue.bits
    }
  }

  def connectTable(
      table: CounterTable,
      in: DecoupledIO[Request],
      queue: DecoupledIO[Request],
      altQueue: DecoupledIO[Request],
  ): Bool = {
    table.io.incr.address := in.bits.address >> blockSizeShift.U
    table.io.incr.enable := in.fire() && in.bits.write
    table.io.decr.address := queue.bits.address >> blockSizeShift.U
    table.io.decr.enable := queue.fire() && queue.bits.write

    table.io.read.address := altQueue.bits.address >> blockSizeShift.U
    table.io.read.enable := altQueue.valid
    // safe for the alternate control queue to proceed
    val altSafe = !(table.io.read.enable && table.io.count =/= 0.U)
    altSafe
  }
}

class ScratchSpec extends FunUnitSpec {
  describe("Scratch") {
    it("should block reads on B until the write on A is complete") {
      test(new Scratch) { m =>
        m.io.a.in.setSourceClock(m.clock)
        m.io.a.out.setSinkClock(m.clock)
        m.io.b.in.setSourceClock(m.clock)
        m.io.b.out.setSinkClock(m.clock)

        m.io.a.in.enqueue(Request(m.depth, 0, true))
        m.io.b.in.enqueue(Request(m.depth, 0, false))

        for (i <- 0 until 5) {
          m.io.a.out.valid.expect(true.B)
          m.io.b.out.valid.expect(false.B)
        }

        m.io.a.out.expectDequeue(Request(m.depth, 0, true))
        m.io.b.out.expectDequeue(Request(m.depth, 0, false))
      }
    }

    it("should block reads on A until the write on B is complete") {
      test(new Scratch) { m =>
        m.io.a.in.setSourceClock(m.clock)
        m.io.a.out.setSinkClock(m.clock)
        m.io.b.in.setSourceClock(m.clock)
        m.io.b.out.setSinkClock(m.clock)

        m.io.a.in.enqueue(Request(m.depth, 0, false))
        m.io.b.in.enqueue(Request(m.depth, 0, true))

        for (i <- 0 until 5) {
          m.io.a.out.valid.expect(false.B)
          m.io.b.out.valid.expect(true.B)
        }

        m.io.b.out.expectDequeue(Request(m.depth, 0, true))
        m.io.a.out.expectDequeue(Request(m.depth, 0, false))
      }
    }

    it(
      "should give priority to A when both queues have reads and writes to same address"
    ) {
      test(new Scratch) { m =>
        m.io.a.in.setSourceClock(m.clock)
        m.io.a.out.setSinkClock(m.clock)
        m.io.b.in.setSourceClock(m.clock)
        m.io.b.out.setSinkClock(m.clock)

        m.io.a.in.enqueue(Request(m.depth, 0, false))
        m.io.a.in.enqueue(Request(m.depth, 0, true))
        m.io.b.in.enqueue(Request(m.depth, 0, false))
        m.io.b.in.enqueue(Request(m.depth, 0, true))

        for (i <- 0 until 5) {
          m.io.a.out.valid.expect(true.B)
          m.io.b.out.valid.expect(false.B)
        }

        m.io.a.out.expectDequeue(Request(m.depth, 0, false))
        m.io.a.out.expectDequeue(Request(m.depth, 0, true))
        m.io.b.out.expectDequeue(Request(m.depth, 0, false))
        m.io.b.out.expectDequeue(Request(m.depth, 0, true))
      }
    }

    it("should not block when A and B are working on different blocks") {
      test(new Scratch) { m =>
        m.io.a.in.setSourceClock(m.clock)
        m.io.a.out.setSinkClock(m.clock)
        m.io.b.in.setSourceClock(m.clock)
        m.io.b.out.setSinkClock(m.clock)

        m.io.a.in.enqueue(Request(m.depth, 0, true))
        m.io.b.in.enqueue(Request(m.depth, m.blockSize, false))

        for (i <- 0 until 5) {
          m.io.a.out.valid.expect(true.B)
          m.io.b.out.valid.expect(true.B)
        }

        m.io.a.out.expectDequeue(Request(m.depth, 0, true))
        m.io.b.out.expectDequeue(Request(m.depth, m.blockSize, false))
      }
    }
  }
}

class CounterTablePort(val size: Int) extends Bundle {
  val address = UInt(log2Ceil(size).W)
  val enable  = Bool()
}

class CounterTable(size: Int, maxCount: Int) extends Module {
  val io = IO(new Bundle {
    val incr  = Input(new CounterTablePort(size))
    val decr  = Input(new CounterTablePort(size))
    val read  = Input(new CounterTablePort(size))
    val count = Output(UInt(log2Ceil(size).W))
  })

  val table = RegInit(VecInit(Array.fill(size)(0.U(log2Ceil(maxCount).W))))

  when(io.read.enable) {
    io.count := table(io.read.address)
  }.otherwise {
    io.count := DontCare
  }

  when(io.incr.address === io.decr.address) {
    val address = io.incr.address
    when(io.incr.enable) {
      when(io.decr.enable) {
        // do nothing
      }.otherwise {
        incr()
      }
    }.otherwise {
      when(io.decr.enable) {
        decr()
      }.otherwise {
        // do nothing
      }
    }
  }.otherwise {
    when(io.incr.enable) {
      incr()
    }
    when(io.decr.enable) {
      decr()
    }
  }

  private def incr(): Unit = {
    table(io.incr.address) := Mux(
      table(io.incr.address) === (maxCount - 1).U,
      table(io.incr.address),
      table(io.incr.address) + 1.U
    )
  }

  private def decr(): Unit = {
    table(io.decr.address) := Mux(
      table(io.decr.address) === 0.U,
      table(io.decr.address),
      table(io.decr.address) - 1.U
    )
  }
}

class CounterTableSpec extends FunUnitSpec {
  describe("CounterTable") {
    describe("when size = 8, maxCount = 16") {
      val size     = 8
      val maxCount = 16
      it("should increment and decrement") {
        test(new CounterTable(size, maxCount)) { m =>
          m.io.read.enable.poke(true.B)
          m.io.incr.enable.poke(true.B)
          for (i <- 0 until size) {
            m.io.incr.address.poke(i.U)
            m.io.read.address.poke(i.U)
            m.clock.step()
          }
          m.io.incr.enable.poke(false.B)
          m.io.decr.enable.poke(true.B)
          for (i <- 0 until size) {
            m.io.decr.address.poke(i.U)
            m.io.read.address.poke(i.U)
            m.clock.step()
          }
          m.io.decr.enable.poke(false.B)
        }
      }
    }
  }
}

class Port[T <: Data](gen: T, depth: Long) extends Bundle {
  val control = Flipped(Decoupled(new Request(depth)))
  val input   = Flipped(Decoupled(gen))
  val output  = Decoupled(gen)

  override def cloneType =
    (new Port(gen, depth)).asInstanceOf[this.type]
}

class Request(val depth: Long) extends Bundle {
  val address = UInt(log2Ceil(depth).W)
  val write   = Bool()
}

object Request {
  def apply(depth: Long, address: Int, write: Boolean): Request = {
    (new Request(depth)).Lit(_.address -> address.U, _.write -> write.B)
  }
}
