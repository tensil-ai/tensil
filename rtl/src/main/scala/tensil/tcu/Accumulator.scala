/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO, Queue, log2Ceil}
import chisel3.experimental.{verification => v}
import tensil.{PlatformConfig, util}
import tensil.util.{DecoupledHelper, Delay, allReady, enqueue}
import tensil.util.decoupled
import tensil.util.decoupled.MultiEnqueue
import tensil.util.decoupled.VecAdder
import tensil.util.decoupled.QueueWithReporting
import tensil.mem.{Mem, MemControl}
import tensil.mem.DualPortMem
import tensil.mem.OutQueue

class Accumulator[T <: Data with Num[T]](
    val gen: T,
    height: Int,
    depth: Long,
    debug: Boolean = false,
)(implicit val platformConfig: PlatformConfig)
    extends Module {
  val io = IO(new Bundle {
    val input          = Flipped(Decoupled(Vec(height, gen)))
    val output         = Decoupled(Vec(height, gen))
    val control        = Flipped(Decoupled(new AccumulatorControl(depth)))
    val wrote          = Decoupled(Bool())
    val tracepoint     = Input(Bool())
    val programCounter = Input(UInt(32.W))
  })

  dontTouch(io.tracepoint)
  dontTouch(io.programCounter)

  val mem = Module(
    new DualPortMem(
      Vec(height, gen),
      depth,
      name = "acc",
      debug = debug,
    )
  )
  val adder   = Module(new VecAdder(gen, height))
  val control = Queue(io.control, 1, pipe = true, flow = true)
  val input   = Queue(io.input, 2)

  val portA        = mem.io.portA
  val portB        = mem.io.portB
  val portAControl = portA.control
  val portBControl = OutQueue(portB.control, 1, pipe = true, flow = true)

  mem.io.programCounter := io.programCounter
  mem.io.tracepoint := io.tracepoint
  portAControl.tieOff()
  portBControl.tieOff()
  portA.status.ready := true.B
  portA.inputStatus.ready := true.B
  portB.status.ready := true.B
  portB.inputStatus.ready := true.B
  io.wrote <> portA.wrote
  portB.wrote.nodeq()

  portB.input <> adder.io.output
  portB.output.ready := false.B

  val inputDemux = OutQueue(
    decoupled.Demux(
      input,
      portA.input,
      adder.io.left,
      name = "acc.input"
    ),
    1,
    pipe = true,
    flow = true
  )
  val memOutputDemux = OutQueue(
    decoupled.Demux(
      portA.output,
      io.output,
      adder.io.right,
      name = "acc.memOutput"
    ),
    1,
    pipe = true,
    flow = true,
  )
  inputDemux.tieOff()
  memOutputDemux.tieOff()

  val readEnqueued = RegInit(false.B)
  readEnqueued := false.B

  val writeEnqueuer = MultiEnqueue(2)
  val readEnqueuer  = MultiEnqueue(2)
  val accEnqueuer   = MultiEnqueue(4)
  writeEnqueuer.tieOff()
  readEnqueuer.tieOff()
  accEnqueuer.tieOff()

  // flag to indicate whether we were performing write accumulate on last cycle
  val writeAccumulating = RegInit(false.B)
  writeAccumulating := false.B

  when(control.bits.write) {
    when(control.bits.accumulate) {
      writeAccumulating := true.B
      control.ready := accEnqueuer.enqueue(
        control.valid,
        // read
        portAControl,
        MemControl(depth)(control.bits.address, false.B),
        memOutputDemux,
        1.U,
        // write acc
        portBControl,
        MemControl(depth)(control.bits.address, true.B),
        inputDemux,
        1.U,
      )
    }.otherwise {
      // just write
      control.ready := writeEnqueuer.enqueue(
        control.valid,
        portAControl,
        MemControl(depth)(control.bits.address, true.B),
        inputDemux,
        0.U,
      )
    }
  }.otherwise {
    // just read
    when(!writeAccumulating) {
      control.ready := readEnqueuer.enqueue(
        control.valid,
        portAControl,
        MemControl(depth)(control.bits.address, false.B),
        memOutputDemux,
        0.U
      )
    }.otherwise {
      // wait for write accumulate to finish
      control.ready := false.B
    }
  }
}
