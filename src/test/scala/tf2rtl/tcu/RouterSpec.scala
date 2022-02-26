package tf2rtl.tcu

import chisel3._
import chiseltest._
import chiseltest.ChiselScalatestTester
import chiseltest.internal.TesterThreadList
import tf2rtl.FunUnitSpec
import tf2rtl.Architecture
import tf2rtl.decoupled.{Driver, VecDriver}

import scala.collection.mutable
import chisel3.experimental.FixedPoint

class RouterSpec extends FunUnitSpec {
  describe("Router") {
    describe("when local depth = 32") {
      val gen = UInt(8.W)
      val arch = Architecture.mkWithDefaults(
        arraySize = 8,
        localDepth = 32,
        accumulatorDepth = 32,
      )

      it("should move data from local to dram0") {
        test(new Router(gen, arch)) { m =>
          m.io.control.setSourceClock(m.clock)
          m.io.host.dataOut.setSinkClock(m.clock)
          m.io.mem.output.setSourceClock(m.clock)

          val n = 10

          val threads = new mutable.ArrayBuffer[TesterThreadList]

          threads += fork {
            for (_ <- 0 until n) {
              m.io.control.enqueue(
                DataFlowControlWithSize(arch.localDepth)(
                  DataFlowControl.memoryToDram0,
                  (arch.localDepth - 1).U
                )
              )
            }
          }

          threads += fork {
            for (_ <- 0 until n) {
              for (i <- 0 until arch.localDepth.toInt) {
                m.io.mem.output.enqueue((i % 256).U)
              }
            }
          }

          threads += fork {
            for (_ <- 0 until n) {
              for (i <- 0 until arch.localDepth.toInt) {
                m.io.host.dataOut.expectDequeue((i % 256).U)
              }
            }
          }

          threads.map(_.join())
        }
      }

      it("should move data from dram0 to local") {}

      it("should move data from local to array to accumulator for matmul") {
        test(new Router(gen, arch)) { m =>
          m.io.control.setSourceClock(m.clock)
          m.io.mem.output.setSourceClock(m.clock)
          m.io.array.input.setSinkClock(m.clock)
          m.io.array.output.setSourceClock(m.clock)
          m.io.acc.input.setSinkClock(m.clock)

          val n = 10

          val threads = new mutable.ArrayBuffer[TesterThreadList]

          threads += fork {
            for (_ <- 0 until n) {
              m.io.control.enqueue(
                DataFlowControlWithSize(arch.localDepth)(
                  DataFlowControl._memoryToArrayToAcc,
                  (arch.localDepth - 1).U
                )
              )
            }
          }

          threads += fork {
            for (_ <- 0 until n) {
              for (i <- 0 until arch.localDepth.toInt) {
                m.io.mem.output.enqueue((i % 256).U)
              }
            }
          }

          threads += fork {
            for (_ <- 0 until n) {
              for (i <- 0 until arch.localDepth.toInt) {
                m.io.array.input.expectDequeue((i % 256).U)
                // m.io.array.output.enqueue((i      % 256).U)
              }
            }
          }

          threads += fork {
            for (_ <- 0 until n) {
              for (i <- 0 until arch.localDepth.toInt) {
                m.io.array.output.enqueue((i % 256).U)
              }
            }
          }

          threads += fork {
            for (_ <- 0 until n) {
              for (i <- 0 until arch.localDepth.toInt) {
                m.io.acc.input.expectDequeue((i % 256).U)
              }
            }
          }

          threads.map(_.join())
        }
      }
      it("should move data from accumulator to local") {}
      it("should move data from local to accumulator") {}
    }
  }
}
