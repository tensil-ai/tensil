/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.stage.ChiselStage
import chisel3.util.{Decoupled, Queue, log2Ceil}
import tensil.mem.{DualPortMem, MemControl}
import tensil.PlatformConfig
import tensil.InstructionLayout
import tensil.tcu.instruction.Instruction
import tensil.util.decoupled.QueueWithReporting
import tensil.util.{WithLast, DecoupledHelper}
import tensil.Architecture
import tensil.ArchitectureDataType
import tensil.util.decoupled
import tensil.mem.Port

class TCU[T <: Data with Num[T]](
    val gen: T,
    val layout: InstructionLayout,
    val options: TCUOptions = TCUOptions()
)(implicit val platformConfig: PlatformConfig)
    extends Module {
  val instructionWidth = layout.instructionSizeBytes * 8
  val width            = layout.arch.arraySize
  val accDepth         = layout.arch.accumulatorDepth
  val localDepth       = layout.arch.localDepth
  val dram0Depth       = layout.arch.dram0Depth
  val dram1Depth       = layout.arch.dram1Depth

  val validateInstructions = options.validateInstructions

  val io = IO(new Bundle {
    val instruction = Flipped(Decoupled(new Instruction(instructionWidth)))
    val status      = Decoupled(new WithLast(new Instruction(instructionWidth)))
    val dram0 = new Bundle {
      val control = Decoupled(new MemControl(layout.arch.dram0Depth))
      val dataIn  = Flipped(Decoupled(Vec(layout.arch.arraySize, gen)))
      val dataOut = Decoupled(Vec(layout.arch.arraySize, gen))
    }
    val dram1 = new Bundle {
      val control = Decoupled(new MemControl(layout.arch.dram1Depth))
      val dataIn  = Flipped(Decoupled(Vec(layout.arch.arraySize, gen)))
      val dataOut = Decoupled(Vec(layout.arch.arraySize, gen))
    }
    val config = new Bundle {
      val dram0AddressOffset  = Output(UInt(platformConfig.axi.addrWidth.W))
      val dram0CacheBehaviour = Output(UInt(4.W))
      val dram1AddressOffset  = Output(UInt(platformConfig.axi.addrWidth.W))
      val dram1CacheBehaviour = Output(UInt(4.W))
    }
    val timeout        = Output(Bool())
    val error          = Output(Bool())
    val tracepoint     = Output(Bool())
    val programCounter = Output(UInt(32.W))
    val sample         = Decoupled(new WithLast(new Sample))
  })

  val decoder = Module(new Decoder(layout.arch, options))
  val array = Module(
    new SystolicArray(gen, layout.arch.arraySize, layout.arch.arraySize)
  )
  val acc = Module(
    new AccumulatorWithALUArray(gen, layout.arch)
  )
  val mem = Module(
    new DualPortMem(
      Vec(layout.arch.arraySize, gen),
      layout.arch.localDepth.toInt,
      name = "main",
      debug = false,
    )
  )
  val router = Module(
    new Router(
      Vec(layout.arch.arraySize, gen),
      layout.arch,
      controlQueueSize = 2
    )
  )
  val hostRouter = Module(
    new HostRouter(
      Vec(layout.arch.arraySize, gen),
      layout.arch,
    )
  )
  val portA = mem.io.portA
  val portB = mem.io.portB

  //// Decoder ////
  decoder.io.instruction <> io.instruction
  io.status <> decoder.io.status
  io.dram0.control <> decoder.io.dram0
  io.dram1.control <> decoder.io.dram1
  io.config <> decoder.io.config
  io.timeout := decoder.io.timeout
  io.error := decoder.io.error
  io.tracepoint := decoder.io.tracepoint
  io.programCounter := decoder.io.programCounter
  io.sample <> decoder.io.sample
  decoder.io.nooped.ready := true.B
  decoder.io.skipped.ready := true.B
  //// Acc ////
  acc.io.control <> QueueWithReporting(decoder.io.acc, 1 << 1) // 6
  acc.io.tracepoint := decoder.io.tracepoint
  acc.io.programCounter := decoder.io.programCounter
  acc.io.nooped.ready := true.B
  acc.io.computed.ready := true.B
  acc.io.wrote.ready := true.B
  //// Array ////
  array.io.control <> QueueWithReporting(decoder.io.array, 1 << 1) // 4
  array.io.weight <> portB.output
  array.io.loaded.ready := true.B
  array.io.ran.ready := true.B
  //// Mem ////
  // port A
  mem.io.tracepoint := decoder.io.tracepoint
  mem.io.programCounter := decoder.io.programCounter
  portA.control <> decoder.io.memPortA
  portA.status.ready := true.B
  portA.inputStatus.ready := true.B
  portA.wrote.ready := true.B
  //// Router ////
  router.io.timeout := decoder.io.timeout
  router.io.tracepoint := decoder.io.tracepoint
  router.io.programCounter := decoder.io.programCounter
  // control
  router.io.control <> decoder.io.dataflow
  // port A
  router.io.mem.output <> portA.output
  portA.input <> router.io.mem.input
  // array
  array.io.input <> router.io.array.input
  router.io.array.output <> array.io.output
  // acc
  acc.io.input <> router.io.acc.input
  router.io.acc.output <> acc.io.output
  //// Host Router ////
  // control
  hostRouter.io.control <> decoder.io.hostDataflow
  // port B
  portB.control <> decoder.io.memPortB
  portB.input <> hostRouter.io.mem.input
  hostRouter.io.mem.output <> portB.output
  portB.status.ready := true.B
  portB.inputStatus.ready := true.B
  portB.wrote.ready := true.B
  // dram0
  hostRouter.io.dram0.dataIn <> io.dram0.dataIn
  io.dram0.dataOut <> hostRouter.io.dram0.dataOut
  // dram1
  hostRouter.io.dram1.dataIn <> io.dram1.dataIn
  io.dram1.dataOut <> hostRouter.io.dram1.dataOut
}
