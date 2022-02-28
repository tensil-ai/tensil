package tensil.util.decoupled

import chisel3._
import chiseltest._
import tensil.FunUnitSpec
import chisel3.experimental.FixedPoint
import tensil.decoupled.decoupledVecToDriver

class VectorDeserializerSpec extends FunUnitSpec {
  describe("VectorDeserializer") {
    describe(
      "when genIn = UInt(64.W) and genOut = FP(16.W, 8.BP) and n = 2 and numScalarsPerWord = 2"
    ) {
      val genIn             = UInt(64.W)
      val genOut            = FixedPoint(16.W, 8.BP)
      val n                 = 2
      val numScalarsPerWord = 2

      it("should deserialize") {
        decoupledTest(
          new VectorDeserializer(genIn, genOut, n, numScalarsPerWord)
        ) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          thread("in") {
            m.io.in.enqueue("h0000010000000000".U)
            m.io.in.enqueue("h0000030000000200".U)
            m.io.in.enqueue("h0000050000000400".U)
          }

          thread("out") {
            m.io.out.expectDequeue(
              (0 until 2).map(_.F(genOut.getWidth.W, genOut.binaryPoint))
            )
            m.io.out.expectDequeue(
              (2 until 4).map(_.F(genOut.getWidth.W, genOut.binaryPoint))
            )
            m.io.out.expectDequeue(
              (4 until 6).map(_.F(genOut.getWidth.W, genOut.binaryPoint))
            )
          }
        }
      }
    }

    describe(
      "when genIn = UInt(64.W) and genOut = FP(16.W, 8.BP) and n = 8 and numScalarsPerWord = 2"
    ) {
      val genIn             = UInt(64.W)
      val genOut            = FixedPoint(16.W, 8.BP)
      val n                 = 8
      val numScalarsPerWord = 2

      it("should deserialize") {
        decoupledTest(
          new VectorDeserializer(genIn, genOut, n, numScalarsPerWord)
        ) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          thread("in") {
            m.io.in.enqueue("h0000010000000000".U)
            m.io.in.enqueue("h0000030000000200".U)
            m.io.in.enqueue("h0000050000000400".U)
            m.io.in.enqueue("h0000070000000600".U)

            m.io.in.enqueue("h0000090000000800".U)
            m.io.in.enqueue("h00000b0000000a00".U)
            m.io.in.enqueue("h00000d0000000c00".U)
            m.io.in.enqueue("h00000f0000000e00".U)
          }

          thread("out") {
            m.io.out.expectDequeue(
              (0 until n).map(_.F(genOut.getWidth.W, genOut.binaryPoint))
            )

            m.io.out.expectDequeue(
              (n until 2 * n).map(_.F(genOut.getWidth.W, genOut.binaryPoint))
            )
          }
        }
      }
    }
  }
}
