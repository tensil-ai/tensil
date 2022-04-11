/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import chiseltest._
import tensil.FunUnitSpec
import tensil.util.WithLast
import chisel3.experimental.BundleLiterals._

class SamplerSpec extends FunUnitSpec {
  private def mkFlagsLiteral(pc: Int): SampleFlags = {
    val k = pc % 16 + 1

    (new SampleFlags).Lit(
      _.instruction -> (new DecoupledFlags)
        .Lit(_.ready -> (k == 1).B, _.valid -> (k == 2).B),
      _.memPortA -> (new DecoupledFlags)
        .Lit(_.ready -> (k == 3).B, _.valid -> (k == 4).B),
      _.memPortB -> (new DecoupledFlags)
        .Lit(_.ready -> (k == 5).B, _.valid -> (k == 6).B),
      _.dram0 -> (new DecoupledFlags)
        .Lit(_.ready -> (k == 7).B, _.valid -> (k == 8).B),
      _.dram1 -> (new DecoupledFlags)
        .Lit(_.ready -> (k == 9).B, _.valid -> (k == 10).B),
      _.dataflow -> (new DecoupledFlags)
        .Lit(_.ready -> (k == 11).B, _.valid -> (k == 12).B),
      _.acc -> (new DecoupledFlags)
        .Lit(_.ready -> (k == 13).B, _.valid -> (k == 14).B),
      _.array -> (new DecoupledFlags)
        .Lit(_.ready -> (k == 15).B, _.valid -> (k == 16).B),
    )
  }

  private def mkSample(m: Sampler, pc: Int, last: Boolean) =
    chiselTypeOf(m.io.sample.bits).Lit(
      _.bits -> (new Sample).Lit(
        _.flags          -> mkFlagsLiteral(pc),
        _.programCounter -> pc.U
      ),
      _.last -> last.B
    )

  private def mkSamples(m: Sampler, pcs: Seq[Int]) =
    pcs
      .grouped(blockSize)
      .map(block => {
        block.zipWithIndex.map {
          case (pc, i) => mkSample(m, pc, i == blockSize - 1)
        }
      })
      .flatten
      .toSeq

  private def mkPcs(interval0: Int, interval1: Int, count: Int, runs: Int) =
    (0 until Math.ceil((count * runs).toFloat / interval0).toInt)
      .map(pc =>
        (Math
          .ceil((pc * interval0).toFloat / interval1)
          .toInt * interval1) % count
      )
      .toSeq

  private def simulateRuns(
      m: Sampler,
      interval: Int,
      count: Int,
      runs: Int
  ) = {
    require(interval > 0)
    require(count > 0)

    val flags = Array(
      m.io.flags.instruction.ready,
      m.io.flags.instruction.valid,
      m.io.flags.memPortA.ready,
      m.io.flags.memPortA.valid,
      m.io.flags.memPortB.ready,
      m.io.flags.memPortB.valid,
      m.io.flags.dram0.ready,
      m.io.flags.dram0.valid,
      m.io.flags.dram1.ready,
      m.io.flags.dram1.valid,
      m.io.flags.dataflow.ready,
      m.io.flags.dataflow.valid,
      m.io.flags.acc.ready,
      m.io.flags.acc.valid,
      m.io.flags.array.ready,
      m.io.flags.array.valid,
    )

    m.io.sampleInterval.poke(interval.U)

    for (i <- 0 until count * runs) {
      val k    = (i % count) % flags.length
      val flag = flags(k)

      flag.poke(true.B)
      m.io.programCounter.poke((i % count).U)

      m.clock.step()

      flag.poke(false.B)
    }
  }

  val blockSize = 32
  val runs      = 2
  val count     = 1000

  def testSampler(interval: Int, delay: Int) = {
    it(s"should sample every $interval cycles with $delay cycle write delay") {
      test(new Sampler(blockSize)) { m =>
        m.io.sample.setSinkClock(m.clock)

        parallel(
          simulateRuns(
            m,
            interval = interval,
            count = count,
            runs = runs
          ), {
            m.clock.step()

            for (
              sample <- mkSamples(
                m,
                mkPcs(Math.max(delay, interval), interval, count, runs)
              )
            ) {
              m.clock.step(delay - 1)
              m.io.sample.expectDequeue(sample)
            }
          }
        )
      }
    }
  }

  testSampler(1, 1)
  testSampler(10, 10)
  testSampler(10, 13)
  testSampler(13, 10)
  testSampler(13, 13)
}
