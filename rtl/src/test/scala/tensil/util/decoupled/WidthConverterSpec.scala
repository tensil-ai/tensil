package tensil.util.decoupled

import chisel3._
import chiseltest._
import tensil.util
import tensil.FunUnitSpec
import chisel3.util.Decoupled

class WidthConverterSpec extends FunUnitSpec {
  describe("WidthConverter") {
    it("should work") {
      decoupledTest(new WidthConverter(64, 72)) { m =>
        m.io.in.setSourceClock(m.clock)
        m.io.out.setSinkClock(m.clock)

        thread("in") {
          m.io.in.enqueue("h0102030405060708".U)
          m.io.in.enqueue("h090a0b0c0d0e0f10".U)
          m.io.in.enqueue("h1112131415161718".U)
        }
        thread("out") {
          m.io.out.expectDequeue("h010203040506070809".U)
          m.io.out.expectDequeue("h0a0b0c0d0e0f101112".U)
        }
      }
    }

    def doTest(inWidth: Int, outWidth: Int) {
      it(
        s"should reproduce data correctly with inWidth=$inWidth and outWidth=$outWidth"
      ) {
        decoupledTest(new WidthConverterTester(inWidth, outWidth)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          val n = util.leastCommonMultiple(inWidth, outWidth) * 10
          // make byte stream of incrementing byte values
          val k = inWidth / 8
          val data =
            for (i <- 0 until n)
              yield (for (j <- 0 until k)
                yield (BigInt(i * k + j) % 256) << (j * 8)).reduce(_ | _)
          // println(data.map(_.toString(16)).mkString("\n"))

          thread("in") {
            for (row <- data) {
              m.io.in.enqueue(row.U)
            }
          }

          thread("out") {
            for (row <- data) {
              m.io.out.expectDequeue(row.U)
            }
          }
        }
      }
    }

    val cases = Array(
      (64, 72),
      (72, 64),
      (8, 64),
      (64, 8),
      (16, 32),
      (32, 16),
    )

    for ((inWidth, outWidth) <- cases) {
      doTest(inWidth, outWidth)
    }
  }
}

class WidthConverterTester(inWidth: Int, outWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(UInt(inWidth.W)))
    val out = Decoupled(UInt(inWidth.W))
  })

  val conv0 = Module(new WidthConverter(inWidth, outWidth))
  val conv1 = Module(new WidthConverter(outWidth, inWidth))

  conv0.io.in <> io.in
  conv1.io.in <> conv0.io.out
  io.out <> conv1.io.out
}
