package tf2rtl.tcu

import chisel3._
import chiseltest._
import chisel3.experimental.BundleLiterals._
import chiseltest.internal.TesterThreadList
import scala.collection.mutable
import tf2rtl.UnitSpec
import tf2rtl.decoupled.{decoupledToDriver, decoupledVecToDriver}
import tf2rtl.tags.Broken

class AccumulatorSpec extends UnitSpec {
  behavior of "Accumulator"

  it should "handle requests on every cycle" in {
    val gen    = SInt(8.W)
    val height = 4
    val depth  = 16

    class PacketCounter extends MultiIOModule {
      val m  = Module(new Accumulator(gen, height, depth))
      val io = IO(m.io.cloneType)
      io <> m.io
      val count = IO(Output(UInt(32.W)))
      val (countValue, wrap) =
        chisel3.util.Counter(Range(0, 1 << 30), m.io.input.fire())
      count := countValue
    }

    decoupledTest(new PacketCounter) { m =>
      m.io.control.setSourceClock(m.clock)
      m.io.input.setSourceClock(m.clock)
      m.io.output.setSinkClock(m.clock)

      val vec: Array[BigInt] = Array(1, 2, 3, 4)
      val accResult          = vec.map(_ * 2)
      val data               = vec.map(_.S)
      val n                  = 100

      thread("control") {
        for (i <- 0 until n) {
          m.io.control.enqueue(
            chiselTypeOf(m.io.control.bits)
              .Lit(_.address -> i.U, _.write -> true.B, _.accumulate -> true.B)
          )
        }
      }

      thread("input") {
        for (_ <- 0 until n) {
          m.io.input.enqueue(data)
        }
      }

      thread("count") {
        m.clock.step(n)
        m.count.expect(n.U)
      }
    }
  }

  it should "read and write values" in {
    val height = 8
    val length = 8
    decoupledTest(new Accumulator(UInt(8.W), height, length)) { m =>
      val testData = for (i <- 0 until height) yield i.U

      m.io.control.setSourceClock(m.clock)
      m.io.input.setSourceClock(m.clock)
      m.io.output.setSinkClock(m.clock)

      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(_.address -> 0.U, _.write -> true.B, _.accumulate -> false.B)
        )
      }

      thread("input") {
        m.io.input.enqueue(testData)
      }

      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(_.address -> 0.U, _.write -> false.B, _.accumulate -> false.B)
        )
      }
      thread("output") {
        m.io.output.expectDequeue(testData)
      }
    }
  }

  it should "accumulate values" in {
    val height = 8
    val length = 8
    decoupledTest(new Accumulator(UInt(8.W), height, length)) { m =>
      val testData   = for (i <- 0 until height) yield i.U
      val expectData = for (i <- 0 until height) yield (2 * i).U

      m.io.control.setSourceClock(m.clock)
      m.io.input.setSourceClock(m.clock)
      m.io.output.setSinkClock(m.clock)

      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(_.address -> 0.U, _.write -> true.B, _.accumulate -> false.B)
        )
      }

      thread("input") {
        m.io.input.enqueue(testData)
      }

      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(_.address -> 0.U, _.write -> false.B, _.accumulate -> false.B)
        )
      }

      thread("output") {
        m.io.output.expectDequeue(testData)
      }

      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(_.address -> 0.U, _.write -> true.B, _.accumulate -> true.B)
        )
      }

      thread("input") {
        m.io.input.enqueue(testData)
      }

      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(_.address -> 0.U, _.write -> false.B, _.accumulate -> false.B)
        )
      }

      thread("output") {
        m.io.output.expectDequeue(expectData)
      }
    }
  }
}
