/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chiseltest._
import tensil.FunUnitSpec
import tensil.decoupled.decoupledVecToDriver

class VecAdderSpec extends FunUnitSpec {
  describe("VecAdder") {
    describe("when gen = UInt(8.W) and size = 4") {
      val gen  = UInt(8.W)
      val size = 4
      it("should add numbers") {
        decoupledTest(new VecAdder(gen, size)) { m =>
          m.io.left.setSourceClock(m.clock)
          m.io.right.setSourceClock(m.clock)
          m.io.output.setSinkClock(m.clock)

          thread("left") {
            m.io.left.enqueue(Array(1, 2, 3, 4).map(_.U))
          }
          thread("right") {
            m.io.right.enqueue(Array(5, 6, 7, 8).map(_.U))
          }
          thread("output") {
            m.io.output.expectDequeue(Array(6, 8, 10, 12).map(_.U))
          }
        }
      }

      it("should add numbers when inputs are staggered") {
        decoupledTest(new VecAdder(gen, size)) { m =>
          m.io.left.setSourceClock(m.clock)
          m.io.right.setSourceClock(m.clock)
          m.io.output.setSinkClock(m.clock)

          thread("output") {
            m.io.output.expectDequeue(Array(6, 8, 10, 12).map(_.U))
          }

          thread("left") {
            m.io.left.enqueue(Array(1, 2, 3, 4).map(_.U))
          }

          thread("right") {
            m.clock.step(10)
            m.io.right.enqueue(Array(5, 6, 7, 8).map(_.U))
          }

        }
      }
    }
  }
}
