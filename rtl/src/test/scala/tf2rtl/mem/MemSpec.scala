package tensil.mem

import chisel3._
import chiseltest._
import chiseltest.internal.TesterThreadList
import scala.collection.mutable
import tensil.{UnitSpec, mem}

class MemSpec extends UnitSpec {
  behavior of "Mem"

  implicit class MemHelper[T <: Data](m: mem.Mem[T]) {
    def setClocks(): Unit = {
      m.io.control.setSourceClock(m.clock)
      m.io.input.setSourceClock(m.clock)
      m.io.output.setSinkClock(m.clock)
    }

    def write(address: UInt, value: T): Unit = {
      thread("control") {
        m.io.control.enqueue(MemControl(m.depth)(address, true.B))
      }
      thread("input") {
        m.io.input.enqueue(value)
      }
    }

    def read(address: UInt, expected: T): Unit = {
      thread("control") {
        m.io.control.enqueue(MemControl(m.depth)(address, false.B))
      }
      thread("output") {
        m.io.output.expectDequeue(expected)
      }
    }
  }

  it should "handle a read on every cycle" in {
    val gen   = UInt(8.W)
    val depth = 16

    decoupledTest(new mem.Mem(gen, depth)) { m =>
      m.setClocks()

      thread("control") {
        // writes
        for (i <- 0 until depth) {
          m.io.control.enqueue(MemControl(m.depth)(i.U, true.B))
        }
        // reads
        for (i <- 0 until depth) {
          m.io.control.enqueue(MemControl(m.depth)(i.U, false.B))
        }
      }

      thread("input") {
        for (i <- 0 until depth) {
          m.io.input.enqueue(i.U)
        }
      }

      thread("output") {
        for (i <- 0 until depth) {
          m.io.output.valid.expect((i != 0).B)
          m.io.output.expectDequeue(i.U)
        }
      }
    }
  }

  it should "set and fetch data" in {
    decoupledTest(new mem.Mem(UInt(8.W), 8)) { m =>
      m.setClocks()

      m.write(3.U, 123.U)
      m.write(7.U, 231.U)
      m.read(3.U, 123.U)
      m.read(7.U, 231.U)
    }
  }

  it should "interleave reads and writes" in {
    decoupledTest(new mem.Mem(UInt(8.W), 8)) { m =>
      m.setClocks()

      m.write(3.U, 123.U)
      m.read(3.U, 123.U)
      m.write(7.U, 231.U)
      m.read(7.U, 231.U)
    }
  }

  it should "handle write addresses and data coming in bursts" in {
    decoupledTest(new mem.Mem(UInt(8.W), 8)) { m =>
      m.setClocks()

      thread("reads") {
        m.clock.step(10)
        m.read(0.U, 0xee.U)
        m.read(1.U, 0xff.U)
        m.read(2.U, 0.U)
        m.read(3.U, 0.U)
      }

      thread("control") {
        m.io.control.enqueue(MemControl(m.depth)(0.U, true.B))
        m.io.control.enqueue(MemControl(m.depth)(1.U, true.B))
      }

      thread("input") {
        m.io.input.enqueue(0xee.U)
        m.io.input.enqueue(0xff.U)
        m.io.input.enqueue(0.U)
        m.io.input.enqueue(0.U)
      }

      thread("control") {
        m.io.control.enqueue(MemControl(m.depth)(2.U, true.B))
        m.io.control.enqueue(MemControl(m.depth)(3.U, true.B))
      }

      m.clock.step(30)
    }
  }

//  it should "not stall when receiving a read command and the read data output is not ready" in {
//    test(new Mem(UInt(8.W), 8)) { m =>
//      m.io.read.address.setSourceClock(m.clock)
//      m.io.read.data.setSinkClock(m.clock)
//      m.io.write.address.setSourceClock(m.clock)
//      m.io.write.data.setSourceClock(m.clock)
//
//      val covered = Array(false, false, false, false)
//      fork {
//        for (i <- 0 until 8) {
//          m.io.write.address.enqueue(i.U)
//        }
//        for (i <- 0 until 8) {
//          m.io.write.address.enqueue(i.U)
//        }
//        covered(0) = true
//      }
//
//      fork {
//        m.clock.step(24)
//        for { i <- 0 until 8 } {
//          m.io.read.address.enqueue(i.U)
//        }
//        covered(1) = true
//      }
//
//      fork {
//        m.clock.step(16)
//        for (i <- 0 until 8) {
//          m.io.write.data.enqueue(i.U)
//        }
//        for (i <- 0 until 8) {
//          m.io.write.data.enqueue((i + 8).U)
//        }
//        covered(2) = true
//      }
//
//      fork {
//        m.clock.step(64)
//        for { i <- 0 until 8 } {
//          m.io.read.data.expectDequeue((i + 8).U)
//        }
//        covered(3) = true
//      }
//
//      m.clock.step(100)
//
//      covered.map(assert(_))
//    }
//  }
}
