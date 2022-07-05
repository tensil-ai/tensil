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
import tensil.mem.{Mem, MemControl, MemControlWithComparable}
import tensil.mem.DualPortMem
import tensil.mem.OutQueue
import tensil.mutex.LockPool
import tensil.mutex.ConditionalReleaseLockControl

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
      platformConfig.accumulatorMemImpl,
      name = "acc",
      debug = debug,
    )
  )
  val adder   = Module(new VecAdder(gen, height))
  val control = io.control
  val input   = io.input

  val portA           = mem.io.portA
  val portB           = mem.io.portB
  val portAControlOut = portA.control
  val portBControlOut = portB.control

  val lockCondGen                               = new MemControlWithComparable(depth)
  def select(r: MemControlWithComparable): UInt = 0.U
  val lockPool = Module(
    new LockPool(lockCondGen, 2, 1, select)
  )
  val portAControl =
    OutQueue(lockPool.io.actor(0).in, 1, pipe = true, flow = true)
  val portBControl = lockPool.io.actor(1).in
  portAControlOut <> lockPool.io.actor(0).out
  portBControlOut <> lockPool.io.actor(1).out
  val lock = lockPool.io.lock
  lock.noenq()
  lockPool.io.locked.nodeq()
  lockPool.io.deadlocked.nodeq()

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

  io.output <> portA.output
  portB.input.noenq()
  adder.io.right <> portB.output

  val inputDemuxModule = Module(
    new decoupled.Demux(chiselTypeOf(input.bits), 2)
  )
  val inputDemux =
    OutQueue(inputDemuxModule.io.sel, 1, pipe = true, flow = true)
  inputDemuxModule.io.in <> input
  adder.io.left <> inputDemuxModule.io.out(1)
  val portAInputMux = OutQueue(
    decoupled.Mux(inputDemuxModule.io.out(0), adder.io.output, portA.input),
    1,
    pipe = true,
    flow = true
  )
  inputDemux.noenq()
  portAInputMux.noenq()

  val writeEnqueuer = MultiEnqueue(4)
  val readEnqueuer  = MultiEnqueue(1)
  val accEnqueuer   = MultiEnqueue(4)
  writeEnqueuer.tieOff()
  readEnqueuer.tieOff()
  accEnqueuer.tieOff()

  when(control.bits.write) {
    when(control.bits.accumulate) {
      control.ready := accEnqueuer.enqueue(
        control.valid,
        // write to port A
        portAControl,
        MemControlWithComparable(depth)(control.bits.address, true.B),
        // read from port B
        portBControl,
        MemControlWithComparable(depth)(control.bits.address, false.B),
        inputDemux,
        1.U,
        portAInputMux,
        1.U,
      )
    }.otherwise {
      // just write
      val req = MemControlWithComparable(depth)(control.bits.address, true.B)
      control.ready := writeEnqueuer.enqueue(
        control.valid,
        portAControl,
        req,
        inputDemux,
        0.U,
        portAInputMux,
        0.U,
        lock,
        lockControl(0.U, req),
      )
    }
  }.otherwise {
    // just read
    control.ready := readEnqueuer.enqueue(
      control.valid,
      portAControl,
      MemControlWithComparable(depth)(control.bits.address, false.B),
    )
  }

  def lockControl(
      by: UInt,
      req: MemControlWithComparable,
  ): ConditionalReleaseLockControl[MemControlWithComparable] = {
    val w = Wire(
      new ConditionalReleaseLockControl(lockCondGen, 2, 1, 1 << 4)
    )
    w.lock := select(req)
    w.acquire := true.B
    w.by := by
    w.delayRelease := 0.U
    w.cond <> req
    w
  }
}
