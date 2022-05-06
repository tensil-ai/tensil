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

class Scratch extends Module {
  val gen               = UInt(8.W)
  val depth             = 1 << 8
  val blockSizeShift    = 4
  val blockSize         = 1 << blockSizeShift
  val maxRequestCount   = 1 << 8
  val controlBufferSize = 8
  val io = IO(new Bundle {

    val a = new Bundle {
      val in  = new Port(gen, depth)
      val out = Flipped(new Port(gen, depth))
    }
    val b = new Bundle {
      val in  = new Port(gen, depth)
      val out = Flipped(new Port(gen, depth))
    }
  })

  // new request on a comes in, what do we do?
  val tableSize = depth / blockSize
  val aTable    = Module(new CounterTable(tableSize, maxRequestCount))
  val bTable    = Module(new CounterTable(tableSize, maxRequestCount))

  val a = io.a
  val b = io.b

  val aControlQueue = Queue(a.in.control, controlBufferSize)
  val bControlQueue = Queue(b.in.control, controlBufferSize)

  aTable.io.incr.address := a.in.control.bits.address
  aTable.io.incr.enable := a.in.control.bits.write
  aTable.io.decr.address := aControlQueue.bits.address
  aTable.io.decr.enable := aControlQueue.bits.write

  bTable.io.incr.address := b.in.control.bits.address
  bTable.io.incr.enable := b.in.control.bits.write
  bTable.io.decr.address := bControlQueue.bits.address
  bTable.io.decr.enable := bControlQueue.bits.write

}

class CounterTablePort(val size: Int) extends Bundle {
  val address = UInt(log2Ceil(size).W)
  val enable  = Bool()
}

class CounterTable(size: Int, maxCount: Int) extends Module {
  val io = IO(new Bundle {
    val incr = Input(new CounterTablePort(size))
    val decr = Input(new CounterTablePort(size))
    val read = Input(new CounterTablePort(size))
    val data = Output(UInt(log2Ceil(size).W))
  })

  val table = RegInit(VecInit(Array.fill(size)(0.U(log2Ceil(maxCount).W))))

  when(io.read.enable) {
    io.data := table(io.read.address)
  }.otherwise {
    io.data := DontCare
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

  def incr(): Unit = {
    table(io.incr.address) := Mux(
      table(io.incr.address) === (maxCount - 1).U,
      table(io.incr.address),
      table(io.incr.address) + 1.U
    )
  }

  def decr(): Unit = {
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

class ScratchSpec extends UnitSpec {
  behavior of "Scratch"

  it should "work" in {
    test(new Scratch) { m => }
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
