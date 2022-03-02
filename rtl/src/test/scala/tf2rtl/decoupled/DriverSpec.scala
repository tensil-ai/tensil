/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.decoupled

import chisel3._
import chisel3.util.Decoupled
import chiseltest._
import tensil.{UnitSpec, decoupled}

class DriverSpec extends UnitSpec {
  behavior of "DecoupledDriver"

  it should "enqueue and dequeue" in {
    test(new DecoupledReg) { m =>
      val driver = new decoupled.Driver(m.io.out)
      driver.setSinkClock(m.clock)
      m.io.in.valid.poke(true.B)
      m.io.in.bits.poke(123.U)
      val result = driver.dequeue
      assert(result.join.litValue == BigInt(123))
    }
  }

  it should "dequeue after an interruption" in {
    test(new DecoupledReg) { m =>
      val driver = new decoupled.Driver(m.io.out)
      driver.setSinkClock(m.clock)
      val result = driver.dequeue
      m.io.in.valid.poke(false.B)
      m.clock.step(3)
      m.io.in.valid.poke(true.B)
      m.io.in.bits.poke(123.U)
      assert(result.join.litValue == BigInt(123))
    }
  }

  it should "dequeue a sequence" in {
    test(new DecoupledReg) { m =>
      val data   = Array(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
      val driver = new decoupled.Driver(m.io.out)
      driver.setSinkClock(m.clock)
      m.io.in.valid.poke(true.B)
      val result = driver.dequeueSeq(10)
      for (d <- data) {
        m.io.in.bits.poke(d.U)
        m.clock.step()
      }
      val resultValue = result.join
      for (i <- 0 until 10)
        assert(resultValue(i).litValue == BigInt(data(i)))
    }
  }

  it should "dequeue a sequence with interruptions" in {
    test(new DecoupledReg) { m =>
      val data   = Array(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
      val driver = new decoupled.Driver(m.io.out)
      driver.setSinkClock(m.clock)
      val result = driver.dequeueSeq(10)
      for (d <- data) {
        while (Math.random() < 0.5) {
          m.io.in.valid.poke(false.B)
          m.clock.step()
        }
        m.io.in.valid.poke(true.B)
        m.io.in.bits.poke(d.U)
        m.clock.step()
      }
      val resultValue = result.join
      for (i <- 0 until 10)
        assert(resultValue(i).litValue == BigInt(data(i)))
    }
  }

//  it should "enqueue multiple ports at once" in {
//    test(new DecoupledDualReg) { m =>
//      m.io.in.a.setSourceClock(m.clock)
//      m.io.in.b.setSourceClock(m.clock)
//
//      m.io.in.b.enqueue(2.U)
//      m.io.in.a.enqueue(1.U)
//      m.io.out.expectDequeue(1.U)
//      m.io.out.expectDequeue(2.U)
//    }
//  }
}

//class DecoupledDualReg extends Module {
//  val io = IO(new Bundle {
//    val in = new Bundle {
//      val a = Flipped(Decoupled(UInt(8.W)))
//      val b = Flipped(Decoupled(UInt(8.W)))
//    }
//    val out = Decoupled(new RoundRobinMuxOutput(UInt(8.W)))
//  })
//  val a = Queue(io.in.a, 1, pipe = true, flow = true)
//  val b = Queue(io.in.b, 1, pipe = true, flow = true)
//  io.out <> RoundRobinMux(Array(a, b))
//}

class DecoupledRegSpec extends UnitSpec {
  behavior of "DecoupledReg"

  it should "enqueue and dequeue" in {
    test(new DecoupledReg) { m =>
      m.io.out.ready.poke(true.B)
      m.io.in.valid.poke(true.B)
      m.io.in.bits.poke(123.U)
      m.io.in.ready.expect(true.B)
      m.clock.step()
      m.io.out.valid.expect(true.B)
      m.io.out.bits.expect(123.U)
    }
  }
}

class DecoupledReg extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(UInt(8.W)))
    val out = Decoupled(UInt(8.W))
  })

  val value = RegInit(0.U(8.W))
  val valid = RegInit(false.B)
  io.out.bits := value
  io.out.valid := valid

  when(io.out.ready) {
    when(io.in.valid) {
      io.in.ready := true.B
      valid := true.B
      value := io.in.bits
    }.otherwise {
      io.in.ready := true.B
      valid := false.B
    }
  }.otherwise {
    when(io.in.valid) {
      when(valid) {
        io.in.ready := false.B
      }.otherwise {
        io.in.ready := true.B
        valid := true.B
        value := io.in.bits
      }
    }.otherwise {
      io.in.ready := !valid
    }
  }
}
