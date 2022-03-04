/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chiseltest._
import tensil.UnitSpec
import tensil.decoupled.{decoupledToDriver, decoupledVecToDriver}
import scala.collection.mutable
import chiseltest.internal.TesterThreadList

class DemuxSpec extends UnitSpec {
  behavior of "DecoupledDemux"

  it should "work with n = 2" in {
    val gen = UInt(8.W)
    val n   = 2

    test(new Demux(gen, n)) { m =>
      m.io.in.setSourceClock(m.clock)
      m.io.sel.setSourceClock(m.clock)

      for (i <- 0 until n) {
        m.io.out(i).setSinkClock(m.clock)
      }
      val threads = new mutable.ArrayBuffer[TesterThreadList]

      threads += fork {
        m.io.in.enqueue(123.U)
        m.io.in.enqueue(234.U)
        m.io.in.enqueue(123.U)
        m.io.in.enqueue(234.U)
      }
      threads += fork {
        m.io.sel.enqueue(0.U)
        m.io.sel.enqueue(1.U)
        m.io.sel.enqueue(1.U)
        m.io.sel.enqueue(0.U)
      }
      threads += fork {
        m.io.out(0).expectDequeue(123.U)
        m.io.out(1).expectDequeue(234.U)
        m.io.out(1).expectDequeue(123.U)
        m.io.out(0).expectDequeue(234.U)
      }

      threads.map(_.join())
    }
  }
}
