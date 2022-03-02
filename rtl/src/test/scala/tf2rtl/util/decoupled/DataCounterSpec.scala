/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chiseltest._
import tensil.FunUnitSpec

class DataCounterSpec extends FunUnitSpec {
  describe("DataCounter") {
    describe("when gen = UInt(32.W), max = 256, numRequests = 8") {
      val gen         = UInt(32.W)
      val max         = 256
      val numRequests = 8
      it("should raise last") {
        decoupledTest(new DataCounter(gen, max, numRequests)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)
          m.io.len.setSourceClock(m.clock)

          val len = 123

          m.io.len.bits.poke((len - 1).U)
          m.io.len.valid.poke(true.B)

          m.io.in.valid.poke(true.B)
          m.io.out.ready.poke(true.B)

          for (i <- 0 until len) {
            m.io.in.bits.poke(i.U)
            m.io.in.ready.expect(true.B)
            m.io.out.bits.expect(i.U)
            m.io.out.valid.expect(true.B)
            if (i == (len - 1)) {
              m.io.last.expect(true.B)
              m.io.len.ready.expect(true.B)
            }
            m.clock.step()
          }
        }
      }
    }
  }
}
