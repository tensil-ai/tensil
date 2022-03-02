/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

import chisel3._
import chisel3.util.log2Ceil
import chisel3.experimental.BundleLiterals._
import chiseltest._
import tensil.FunUnitSpec
import tensil.tcu.DataFlowControl
import tensil.tcu.DataFlowControlWithSize

class SizeHandlerSpec extends FunUnitSpec {
  describe("SizeHandler") {
    describe(
      "when inGen = DataFlowControl with Size and outGen = DataFlowControl and depth = 256"
    ) {
      val depth  = 256
      val inGen  = new DataFlowControlWithSize(depth)
      val outGen = new DataFlowControl
      it("should repeat request `size` times") {
        test(new SizeHandler(inGen, outGen, depth)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          val size = 99
          val kind = DataFlowControl.dram0ToMemory

          val t = fork {
            m.io.in.enqueue(
              inGen.Lit(_.kind -> kind, _.size -> (size - 1).U)
            )
          }
          for (i <- 0 until size) {
            m.io.out.expectDequeue(outGen.Lit(_.kind -> kind))
          }
          t.join()
        }
      }

      it("should handle breaks in output readiness") {
        test(new SizeHandler(inGen, outGen, depth)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          val size = 256
          val kind = DataFlowControl._arrayToAcc

          val t = fork {
            m.io.in.enqueue(
              inGen.Lit(_.kind -> kind, _.size -> (size - 1).U)
            )
          }
          for (i <- 0 until size) {
            // random length break in readiness
            m.clock.step(Math.round(Math.random() * 10).toInt)
            m.io.out.expectDequeue(outGen.Lit(_.kind -> kind))
          }
          m.clock.step(20)
          m.io.out.valid.expect(false.B)
          t.join()
        }
      }
    }
  }
}
