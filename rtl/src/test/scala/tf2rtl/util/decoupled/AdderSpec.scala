/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chiseltest._
import tensil.FunUnitSpec
import tensil.decoupled.{Driver => DecoupledDriver}

class AdderSpec extends FunUnitSpec {
  describe("DecoupledAdder") {
    describe("when n = 2") {
      it("#0 should add two numbers") {
        test(new Adder) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          var covered = false

          fork {
            m.io.out.expectDequeue((123 + 456).U)
            covered = true
          }
          m.io.in.enqueue(123.U)
          m.io.in.enqueue(456.U)

          m.clock.step(10)

          assert(covered)
        }
      }

      it("#1 should add two numbers repeatedly") {
        test(new Adder) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          var covered = false

          fork {
            m.io.out.expectDequeue((123 + 456).U)
            m.io.out.expectDequeue((789 + 321).U)
            covered = true
          }
          m.io.in.enqueue(123.U)
          m.io.in.enqueue(456.U)
          m.io.in.enqueue(789.U)
          m.io.in.enqueue(321.U)

          m.clock.step(10)

          assert(covered)
        }
      }

//      it("#2 should handle gaps in input") {
//        test(new DecoupledAdder) { m => }
//      }
    }

    describe("when n = 4") {
      it("#3 should add four numbers") {
        test(new Adder(4)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          var covered = false

          fork {
            m.io.out.expectDequeue((123 + 456 + 789 + 321).U)
            covered = true
          }
          m.io.in.enqueue(123.U)
          m.io.in.enqueue(456.U)
          m.io.in.enqueue(789.U)
          m.io.in.enqueue(321.U)

          m.clock.step(10)

          assert(covered)
        }
      }
    }
  }
}
