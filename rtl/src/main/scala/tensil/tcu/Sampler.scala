/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import chisel3.util.Fill
import chisel3.util.Decoupled
import chisel3.util.DecoupledIO
import tensil.mem.MemControl
import tensil.InstructionLayout
import tensil.tcu.instruction.Instruction
import tensil.util.decoupled.QueueWithReporting
import chisel3.util.Queue
import tensil.util.WithLast

class DecoupledFlags extends Bundle {
  val ready = Bool()
  val valid = Bool()

  def connect(bus: DecoupledIO[Data]): Unit = {
    ready := bus.ready
    valid := bus.valid
  }
}

class SampleFlags extends Bundle {
  val instruction = new DecoupledFlags
  val memPortA    = new DecoupledFlags
  val memPortB    = new DecoupledFlags
  val dram0       = new DecoupledFlags
  val dram1       = new DecoupledFlags
  val dataflow    = new DecoupledFlags
  val acc         = new DecoupledFlags
  val array       = new DecoupledFlags
}

class Sample extends Bundle {
  val flags          = new SampleFlags
  val programCounter = UInt(32.W)
}

class Sampler(blockSize: Int) extends Module {
  val io = IO(new Bundle {
    val flags          = Input(new SampleFlags)
    val programCounter = Input(UInt(32.W))
    val sampleInterval = Input(UInt(16.W))

    val sample = Decoupled(new WithLast(new Sample))
  })

  val cycleCounter = RegInit(0.U(16.W))

  val outputCounter = RegInit(0.U(32.W))
  val outputSample  = RegInit(0.U.asTypeOf(new Sample()))
  val outputLast    = RegInit(false.B)
  val outputValid   = RegInit(false.B)

  val sample      = Wire(new Sample())
  val sampleReady = Wire(Bool())

  io.sample.valid := outputValid
  io.sample.bits.bits := outputSample
  io.sample.bits.last := outputLast

  when(io.sampleInterval =/= 0.U) {
    sample.programCounter := io.programCounter
    sample.flags := io.flags

    when(cycleCounter === 0.U) {
      sampleReady := true.B

      cycleCounter := (io.sampleInterval - 1.U)
    }.otherwise {
      sampleReady := false.B

      cycleCounter := cycleCounter - 1.U
    }
  }.otherwise {
    sample := WireDefault(new Sample(), 0.U.asTypeOf(new Sample()))
    sampleReady := false.B

    cycleCounter := 0.U
  }

  when(!outputValid || io.sample.ready) {
    when(sampleReady) {
      when(outputCounter === (blockSize - 1).U) {
        outputLast := true.B
        outputCounter := 0.U
      }.otherwise {
        outputLast := false.B
        outputCounter := outputCounter + 1.U
      }

      outputValid := true.B
      outputSample := sample
    }.otherwise {
      outputValid := false.B
      outputLast := false.B
    }
  }
}
