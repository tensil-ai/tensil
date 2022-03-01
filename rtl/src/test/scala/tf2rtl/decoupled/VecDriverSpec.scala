package tensil.decoupled

import chisel3._
import chisel3.util.Decoupled
import chiseltest._
import tensil.UnitSpec

class VecDriverSpec extends UnitSpec {
  behavior of "DecoupledVecDriver"

  def pokeVec[T <: Data](v: Vec[T], data: Seq[T]): Unit = {
    for (i <- v.indices) {
      v(i).poke(data(i))
    }
  }

  def expectVec[T <: Data](v: Vec[T], data: Seq[T]): Unit = {
    for (i <- v.indices) {
      v(i).expect(data(i))
    }
  }

  def assertSeqEqual[T](a: Seq[T], b: Seq[T]): Unit = {
    assert(a.length == b.length)
    for (i <- a.indices) {
      assert(a(i) == b(i))
    }
  }

  it should "enqueue and dequeue" in {
    test(new DecoupledVecReg(2)) { m =>
      val driver = new VecDriver(m.io.out)
      driver.setSinkClock(m.clock)
      m.io.in.valid.poke(true.B)
      pokeVec(m.io.in.bits, Array(123.U, 231.U))
      val result = driver.dequeue
      assertSeqEqual(
        result.join.map(_.litValue),
        Array(BigInt(123), BigInt(231))
      )
    }
  }

  it should "dequeue after an interruption" in {
    test(new DecoupledVecReg(2)) { m =>
      val driver = new VecDriver(m.io.out)
      driver.setSinkClock(m.clock)
      val result = driver.dequeue
      m.io.in.valid.poke(false.B)
      m.clock.step(3)
      m.io.in.valid.poke(true.B)
      pokeVec(m.io.in.bits, Array(123.U, 231.U))
      assertSeqEqual(
        result.join.map(_.litValue),
        Array(BigInt(123), BigInt(231))
      )
    }
  }

  it should "dequeue a sequence" in {
    test(new DecoupledVecReg(2)) { m =>
      val data   = Array.tabulate(10, 2)({ case (i, j) => BigInt(2 * i + j) })
      val driver = new VecDriver(m.io.out)
      driver.setSinkClock(m.clock)
      m.io.in.valid.poke(true.B)
      val result = driver.dequeueSeq(10)
      for (d <- data) {
        pokeVec(m.io.in.bits, d.map(_.U))
        m.clock.step()
      }
      val resultValue = result.join
      for (i <- 0 until 10)
        assertSeqEqual(resultValue(i).map(_.litValue), data(i))
    }
  }

  it should "dequeue a sequence with interruptions" in {
    test(new DecoupledVecReg(2)) { m =>
      val data   = Array.tabulate(10, 2)({ case (i, j) => BigInt(2 * i + j) })
      val driver = new VecDriver(m.io.out)
      driver.setSinkClock(m.clock)
      val result = driver.dequeueSeq(10)
      for (d <- data) {
        while (Math.random() < 0.5) {
          m.io.in.valid.poke(false.B)
          m.clock.step()
        }
        m.io.in.valid.poke(true.B)
        pokeVec(m.io.in.bits, d.map(_.U))
        m.clock.step()
      }
      val resultValue = result.join
      for (i <- 0 until 10)
        assertSeqEqual(resultValue(i).map(_.litValue()), data(i))
    }
  }
}

class DecoupledVecRegSpec extends UnitSpec {
  behavior of "DecoupledVecReg"

  def pokeVec[T <: Data](v: Vec[T], data: Seq[T]): Unit = {
    for (i <- v.indices) {
      v(i).poke(data(i))
    }
  }

  def expectVec[T <: Data](v: Vec[T], data: Seq[T]): Unit = {
    for (i <- v.indices) {
      v(i).expect(data(i))
    }
  }

  it should "enqueue and dequeue" in {
    test(new DecoupledVecReg(2)) { m =>
      m.io.out.ready.poke(true.B)
      m.io.in.valid.poke(true.B)
      pokeVec(m.io.in.bits, Array(123.U, 231.U))
//      m.io.in.bits.poke(123.U)
      m.io.in.ready.expect(true.B)
      m.clock.step()
      m.io.out.valid.expect(true.B)
      expectVec(m.io.in.bits, Array(123.U, 231.U))
//      m.io.out.bits.expect(123.U)
    }
  }
}

class DecoupledVecReg(n: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(Vec(n, UInt(8.W))))
    val out = Decoupled(Vec(n, UInt(8.W)))
  })

  val value = RegInit(VecInit(Array.fill(n)(0.U(8.W))))
  val valid = RegInit(false.B)
  io.out.bits := value
  io.out.valid := valid

  when(io.out.ready) {
    when(io.in.valid) {
      io.in.ready := true.B
      valid := true.B
      value <> io.in.bits
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
        value <> io.in.bits
      }
    }.otherwise {
      io.in.ready := !valid
    }
  }
}
