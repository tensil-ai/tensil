/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chiseltest._
import tensil.FunUnitSpec
import chisel3.experimental.FixedPoint
import tensil.decoupled.decoupledVecToDriver

class VectorSerializerSpec extends FunUnitSpec {
  describe("VectorSerializer") {
    describe(
      "when genIn = FP(16.W, 8.BP) and genOut = UInt(64.W) and n = 8 and numScalarsPerWord = 2"
    ) {
      val genIn             = FixedPoint(16.W, 8.BP)
      val genOut            = UInt(64.W)
      val n                 = 8
      val numScalarsPerWord = 2

      it("should serialize") {
        test(new VectorSerializer(genIn, genOut, n, numScalarsPerWord)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          m.io.in.enqueue(
            (0 until n).map(_.F(genIn.getWidth.W, genIn.binaryPoint))
          )
          m.io.out.expectDequeue("h0000010000000000".U)
          m.io.out.expectDequeue("h0000030000000200".U)
          m.io.out.expectDequeue("h0000050000000400".U)
          m.io.out.expectDequeue("h0000070000000600".U)

          m.io.in.enqueue(
            (n until 2 * n).map(_.F(genIn.getWidth.W, genIn.binaryPoint))
          )
          m.io.out.expectDequeue("h0000090000000800".U)
          m.io.out.expectDequeue("h00000b0000000a00".U)
          m.io.out.expectDequeue("h00000d0000000c00".U)
          m.io.out.expectDequeue("h00000f0000000e00".U)
        }
      }
    }
  }
}
