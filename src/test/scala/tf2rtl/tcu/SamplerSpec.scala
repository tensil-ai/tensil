package tf2rtl.tcu

import chisel3._
import chiseltest._
import tf2rtl.FunUnitSpec
import tf2rtl.util.WithLast
import chisel3.experimental.BundleLiterals._

class SamplerSpec extends FunUnitSpec {
  private val InvalidPC = -1

  private def mkFlagsLiteral(pc: Int): SampleFlags = {
    val k = if (pc != InvalidPC) pc % 16 + 1 else 0

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
        _.flags -> mkFlagsLiteral(pc),
        _.programCounter -> (if (pc != InvalidPC) pc.U
                             else "h_ffff_ffff".U)
      ),
      _.last -> last.B
    )

  private def mkSamples(m: Sampler, pcs: Seq[Int]) =
    pcs
      .grouped(BlockSize)
      .map(block => {
        block.zipWithIndex.map {
          case (pc, i) => mkSample(m, pc, i == BlockSize - 1)
        }
      })
      .flatten
      .toSeq

  private def simulateRuns(
      m: Sampler,
      interval: Int,
      count: Int,
      runs: Int,
      wait: Int
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

    for (_ <- 0 until runs) {
      m.io.sampleInterval.poke(interval.U)

      for (i <- 0 until count) {
        val k    = i % flags.length
        val flag = flags(k)

        flag.poke(true.B)
        m.io.programCounter.poke(i.U)

        m.clock.step()

        flag.poke(false.B)

        if (i == count - 1) {
          m.io.sampleInterval.poke(0.U)

          for (_ <- 0 until wait)
            m.clock.step()
        }
      }
    }
  }

  val BlockSize = 10

  describe("Sampler") {
    it("should sample every cycle") {
      test(new Sampler(BlockSize)) { m =>
        m.io.sample.setSinkClock(m.clock)

        val count = 1000
        val runs  = 2
        val wait  = 100

        parallel(
          simulateRuns(
            m,
            interval = 1,
            count = count,
            runs = runs,
            wait = wait
          ), {
            val pcs =
              Seq
                .fill(runs)(
                  (0 until count).toSeq ++ Seq.fill(wait)(InvalidPC)
                )
                .flatten

            m.io.sample.expectDequeueSeq(mkSamples(m, pcs))
          }
        )
      }
    }

    it("should sample every 13 cycles") {
      test(new Sampler(BlockSize)) { m =>
        m.io.sample.setSinkClock(m.clock)

        val interval = 13
        val count    = 1000
        val runs     = 2
        val wait     = 100

        parallel(
          simulateRuns(
            m,
            interval = interval,
            count = count,
            runs = runs,
            wait = wait
          ), {
            val pcs =
              Seq
                .fill(runs)(
                  (0 until count / 13)
                    .map(pc => (pc + 1) * 13 - 1)
                    .toSeq ++ Seq.fill(wait)(InvalidPC)
                )
                .flatten

            m.io.sample.expectDequeueSeq(mkSamples(m, pcs))
          }
        )
      }
    }

    it("should skip sample when stream is delayed") {
      test(new Sampler(BlockSize)) { m =>
        m.io.sample.setSinkClock(m.clock)

        val count = 1000
        val runs  = 2
        val wait  = 100
        val delay = 9

        parallel(
          simulateRuns(
            m,
            interval = 1,
            count = count,
            runs = runs,
            wait = wait
          ), {
            val pcs =
              Seq
                .fill(runs)(
                  (0 until count / (delay + 1))
                    .map(pc => pc * (delay + 1))
                    .toSeq ++ Seq.fill(wait / (delay + 1))(InvalidPC)
                )
                .flatten

            for (sample <- mkSamples(m, pcs)) {
              m.clock.step(delay)
              m.io.sample.expectDequeue(sample)
            }
          }
        )
      }
    }

    it("should sample invalid PCs when sampleInterval = 0") {
      test(new Sampler(BlockSize)) { m =>
        m.io.sample.setSinkClock(m.clock)

        val count = 1000

        val pcs = Seq.fill(count)(InvalidPC)

        m.io.sample.expectDequeueSeq(mkSamples(m, pcs))
      }
    }
  }
}
