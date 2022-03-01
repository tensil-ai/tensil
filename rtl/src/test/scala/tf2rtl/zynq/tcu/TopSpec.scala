package tensil.zynq.tcu

import chisel3._
import chiseltest._
import chisel3.tester.experimental.TestOptionBuilder._
import chiseltest.internal.VerilatorBackendAnnotation
import tensil.FunUnitSpec
import tensil.axi.AXI4Stream
import tensil.axi.AXI4StreamToAXI4StreamDriver
import tensil.tags.Verilator
import tensil.util.Environment._

class TopSpec extends FunUnitSpec {
  class TopWrapper extends MultiIOModule {
    val instruction = IO(Flipped(new AXI4Stream(32)))
    val dataIn      = IO(Flipped(new AXI4Stream(32)))
    val weightsIn   = IO(Flipped(new AXI4Stream(32)))
    val dataOut     = IO(new AXI4Stream(32))
    val status      = IO(new AXI4Stream(32))

    val top = Module(new Top)
    top.clock := clock
    top.reset := reset
    top.instruction <> instruction
//    top.dataIn <> dataIn
//    top.weightsIn <> weightsIn
//    top.dataOut <> dataOut
//    top.status <> status
//
//    top.weightMemStatus.tieOff()
//    top.memStatus.tieOff()
//    top.weightMemInputStatus.tieOff()
  }

  implicit val environment: Environment = Simulation

  // describe("Top") {
  //   it("should read out same data that was written in") {
  //     test(new TopWrapper) { m =>
  //       m.instruction.setSourceClock(m.clock)
  //       m.dataIn.setSourceClock(m.clock)
  //       m.weightsIn.setSourceClock(m.clock)
  //       m.dataOut.setSinkClock(m.clock)
  //       m.status.setSinkClock(m.clock)

  //       m.instruction.enqueue(0x40000100.U)
  //       m.dataIn.enqueue(0x02000100.U)
  //       m.dataIn.enqueue(0x04000300.U)
  //       m.dataIn.enqueue(0x06000500.U)
  //       m.dataIn.enqueue(0x08000700.U)

  //       m.instruction.enqueue(0x50000100.U)
  //       m.dataOut.expectDequeue(0x02000100.U)
  //       m.dataOut.expectDequeue(0x04000300.U)
  //       m.dataOut.expectDequeue(0x06000500.U)
  //       m.dataOut.expectDequeue(0x08000700.U)
  //     }
  //   }

  //   it("should work on data from real test", Verilator) {
  //     test(new TopWrapper).withAnnotations(Seq(VerilatorBackendAnnotation)) {
  //       m =>
  //         m.instruction.setSourceClock(m.clock)
  //         m.dataIn.setSourceClock(m.clock)
  //         m.weightsIn.setSourceClock(m.clock)
  //         m.dataOut.setSinkClock(m.clock)
  //         m.status.setSinkClock(m.clock)

  //         def enqueueWeightsZero(n: Int): Unit =
  //           for (i <- 0 until n) m.weightsIn.enqueue(0x00000000.U)

  //         val covered = Array(false, false)

  //         fork {
  //           m.instruction.enqueue(0x40000100.U)
  //           m.instruction.enqueue(0x60000900.U)
  //           covered(0) = true
  //         }

  //         fork {
  //           m.status.expectDequeue(0x40000100.U)
  //           m.status.expectDequeue(0x60000900.U)
  //           covered(1) = true
  //         }

  //         fork {
  //           m.weightsIn.enqueue(0x00e30000.U)
  //           enqueueWeightsZero(3)
  //           m.weightsIn.enqueue(0x00e9ff2e.U)
  //           enqueueWeightsZero(3)
  //           // have to do this to avoid sign bit since scala doesn't have unsigned literals
  //           val w: Long = 0x00000000ff3400d2L
  //           m.weightsIn.enqueue(w.U)
  //           //        m.weightsIn.enqueue(0xff3400d2.U)
  //           enqueueWeightsZero(27)
  //           m.weightsIn.enqueue(0x00000001.U)
  //           enqueueWeightsZero(3)
  //           m.weightsIn.enqueue(0x00000135.U)
  //           enqueueWeightsZero(3)
  //           m.weightsIn.enqueue(0x0000013e.U)
  //           enqueueWeightsZero(27)
  //         }

  //         fork {
  //           m.clock.step(100)
  //           m.dataIn.enqueue(0x01000000.U)
  //           m.dataIn.enqueue(0x03000200.U)
  //           m.dataIn.enqueue(0x05000400.U)
  //           m.dataIn.enqueue(0x07000600.U)
  //         }

  //         m.clock.step(200)

  //         covered.map(assert(_))

  //     }
  //   }
  // }
}
