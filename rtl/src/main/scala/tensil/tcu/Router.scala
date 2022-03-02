/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO, Queue}
import tensil.util.{DecoupledHelper, decoupled, reportThroughput}
import tensil.util.decoupled.{MultiEnqueue, MuxSel, MuxSelWithSize}
import tensil.util.decoupled.QueueWithReporting
import tensil.Architecture
import tensil.mem.SizeHandler

class Router[T <: Data](
    val gen: T,
    val arch: Architecture,
    controlQueueSize: Int = 2,
) extends Module {
  val io = IO(new Bundle {
    val control = Flipped(
      Decoupled(
        new DataFlowControlWithSize(arch.localDepth)
      )
    )
    val mem = new Bundle {
      val output = Flipped(Decoupled(gen))
      val input  = Decoupled(gen)
    }
    val host = new Bundle {
      val dataIn  = Flipped(Decoupled(gen))
      val dataOut = Decoupled(gen)
    }
    val array = new Bundle {
      val input  = Decoupled(gen)
      val output = Flipped(Decoupled(gen))
    }
    val acc = new Bundle {
      val output = Flipped(Decoupled(gen))
      val input  = Decoupled(gen)
    }
    val timeout        = Input(Bool())
    val tracepoint     = Input(Bool())
    val programCounter = Input(UInt(32.W))
  })

  dontTouch(io.timeout)
  dontTouch(io.tracepoint)
  dontTouch(io.programCounter)

  val control = io.control

  // reportThroughput(control, 100, "Router")

  // routing control muxes
  val memReadDataDemuxModule = Module(
    new decoupled.Demux(
      chiselTypeOf(io.mem.output.bits),
      3,
      name = "router.memReadData"
    )
  )
  memReadDataDemuxModule.io.in <> io.mem.output
  io.host.dataOut <> memReadDataDemuxModule.io.out(0)
  io.array.input <> memReadDataDemuxModule.io.out(1)

  val memWriteDataMuxModule = Module(
    new decoupled.Mux(
      chiselTypeOf(io.mem.input.bits),
      2,
      name = "router.memWriteData"
    )
  )
  memWriteDataMuxModule.io.in(0) <> io.host.dataIn
  memWriteDataMuxModule.io.in(1) <> io.acc.output
  io.mem.input <> memWriteDataMuxModule.io.out

  val accWriteDataMuxModule = Module(
    new decoupled.Mux(
      chiselTypeOf(io.acc.input.bits),
      2,
      name = "router.accWriteData"
    )
  )
  accWriteDataMuxModule.io.in(0) <> io.array.output
  accWriteDataMuxModule.io.in(1) <> memReadDataDemuxModule.io.out(2)
  io.acc.input <> accWriteDataMuxModule.io.out

  // size handlers
  def makeSizeHandler(
      n: Int,
      name: String,
      muxSel: DecoupledIO[UInt]
  ): (DecoupledIO[MuxSelWithSize], UInt => MuxSelWithSize) = {
    val inGen  = new MuxSelWithSize(n, arch.localDepth)
    val outGen = new MuxSel(n)
    val sizeHandler = Module(
      new SizeHandler(inGen, outGen, arch.localDepth, name = name)
    )
    val muxSelWithSize = sizeHandler.io.in
    muxSel.bits := sizeHandler.io.out.bits.sel
    muxSel.valid := sizeHandler.io.out.valid
    sizeHandler.io.out.ready := muxSel.ready

    def muxSelLit(sel: UInt): MuxSelWithSize =
      MuxSelWithSize(n, arch.localDepth, sel, control.bits.size)
    (muxSelWithSize, muxSelLit)
  }
  val (memReadDataDemux, memReadDataDemuxSel) =
    makeSizeHandler(3, "memReadDataDemux", memReadDataDemuxModule.io.sel)
  val (memWriteDataMux, memWriteDataMuxSel) =
    makeSizeHandler(2, "memWriteDataMux", memWriteDataMuxModule.io.sel)
  val (accWriteDataMux, accWriteDataMuxSel) =
    makeSizeHandler(2, "accWriteDataMux", accWriteDataMuxModule.io.sel)
  memReadDataDemux.tieOff()
  memWriteDataMux.tieOff()
  accWriteDataMux.tieOff()

  val enqueuer1 = MultiEnqueue(1)
  val enqueuer2 = MultiEnqueue(2)
  enqueuer1.tieOff()
  enqueuer2.tieOff()

  when(control.bits.kind === DataFlowControl._memoryToArrayToAcc) {
    control.ready := enqueuer2.enqueue(
      control.valid,
      memReadDataDemux,
      memReadDataDemuxSel(1.U),
      accWriteDataMux,
      accWriteDataMuxSel(0.U),
    )
  }.elsewhen(control.bits.kind === DataFlowControl._arrayToAcc) {
    control.ready := enqueuer1.enqueue(
      control.valid,
      accWriteDataMux,
      accWriteDataMuxSel(0.U),
    )
  }.elsewhen(control.bits.kind === DataFlowControl.accumulatorToMemory) {
    control.ready := enqueuer1.enqueue(
      control.valid,
      memWriteDataMux,
      memWriteDataMuxSel(1.U),
    )
  }.elsewhen(
    control.bits.kind === DataFlowControl.memoryToAccumulator || control.bits.kind === DataFlowControl.memoryToAccumulatorAccumulate
  ) {
    control.ready := enqueuer2.enqueue(
      control.valid,
      memReadDataDemux,
      memReadDataDemuxSel(2.U),
      accWriteDataMux,
      accWriteDataMuxSel(1.U),
    )
  }.elsewhen(control.bits.kind === DataFlowControl.dram0ToMemory) {
    control.ready := enqueuer1.enqueue(
      control.valid,
      memWriteDataMux,
      memWriteDataMuxSel(0.U),
    )
  }.elsewhen(control.bits.kind === DataFlowControl.memoryToDram0) {
    control.ready := enqueuer1.enqueue(
      control.valid,
      memReadDataDemux,
      memReadDataDemuxSel(0.U),
    )
  }.otherwise {
    control.ready := true.B
  }
}

object Router {
  val dataflows = Array(
    DataFlowControl._memoryToArrayToAcc,
    DataFlowControl._arrayToAcc,
    DataFlowControl.accumulatorToMemory,
    DataFlowControl.memoryToAccumulator,
    DataFlowControl.memoryToAccumulatorAccumulate,
    DataFlowControl.dram0ToMemory,
    DataFlowControl.memoryToDram0,
  )
}
