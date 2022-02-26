package tf2rtl.tcu

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.stage.ChiselStage
import chisel3.util.{Decoupled, Queue, log2Ceil}
import tf2rtl.mem.{DualPortMem, MemControl}
import tf2rtl.PlatformConfig
import tf2rtl.tools.compiler.InstructionLayout
import tf2rtl.tcu.instruction.Instruction
import tf2rtl.util.decoupled.QueueWithReporting
import tf2rtl.util.{WithLast, DecoupledHelper}
import tf2rtl.Architecture
import tf2rtl.ArchitectureDataType

class TCU[T <: Data with Num[T]](
    val gen: T,
    val arch: Architecture,
)(implicit val platformConfig: PlatformConfig)
    extends Module {
  val layout = new InstructionLayout(
    arch
  )
  val instructionWidth     = layout.instructionSizeBytes * 8
  val width                = arch.arraySize
  val accDepth             = arch.accumulatorDepth
  val localDepth           = arch.localDepth
  val dram0Depth           = arch.dram0Depth
  val dram1Depth           = arch.dram1Depth
  val validateInstructions = arch.validateInstructions

  val io = IO(new Bundle {
    val instruction = Flipped(Decoupled(new Instruction(instructionWidth)))
    val status      = Decoupled(new WithLast(new Instruction(instructionWidth)))
    val dram0 = new Bundle {
      val control = Decoupled(new MemControl(arch.dram0Depth))
      val dataIn  = Flipped(Decoupled(Vec(arch.arraySize, gen)))
      val dataOut = Decoupled(Vec(arch.arraySize, gen))
    }
    val dram1 = new Bundle {
      val control = Decoupled(new MemControl(arch.dram1Depth))
      val dataIn  = Flipped(Decoupled(Vec(arch.arraySize, gen)))
      val dataOut = Decoupled(Vec(arch.arraySize, gen))
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

  val decoder = Module(new Decoder(arch))
  val array   = Module(new SystolicArray(gen, arch.arraySize, arch.arraySize))
  val acc = Module(
    new AccumulatorWithALUArray(gen, arch)
  )
  val mem = Module(
    new DualPortMem(
      Vec(arch.arraySize, gen),
      arch.localDepth.toInt,
      name = "main",
      debug = false,
    )
  )
  val router = Module(
    // new Router(Vec(arch.arraySize, gen), arch, controlQueueSize = 50)
    new Router(Vec(arch.arraySize, gen), arch, controlQueueSize = 2)
  )

  val dataPort    = mem.io.portA
  val weightsPort = mem.io.portB

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
  array.io.weight <> weightsPort.output
  array.io.loaded.ready := true.B
  array.io.ran.ready := true.B
  //// Mem ////
  mem.io.tracepoint := decoder.io.tracepoint
  mem.io.programCounter := decoder.io.programCounter
  dataPort.control <> decoder.io.memPortA
  dataPort.status.ready := true.B
  dataPort.inputStatus.ready := true.B
  dataPort.wrote.ready := true.B
  //// WeightMem ////
  weightsPort.control <> decoder.io.memPortB
  weightsPort.input <> io.dram1.dataIn
  weightsPort.status.ready := true.B
  weightsPort.inputStatus.ready := true.B
  weightsPort.wrote.ready := true.B
  ///// Router ////
  router.io.timeout := decoder.io.timeout
  router.io.tracepoint := decoder.io.tracepoint
  router.io.programCounter := decoder.io.programCounter
  // control
  router.io.control <> decoder.io.dataflow
  // mem
  router.io.mem.output <> dataPort.output
  dataPort.input <> router.io.mem.input
  // array
  array.io.input <> router.io.array.input
  router.io.array.output <> array.io.output
  // acc
  acc.io.input <> router.io.acc.input
  router.io.acc.output <> acc.io.output
  // host
  router.io.host.dataIn <> io.dram0.dataIn
  io.dram0.dataOut <> router.io.host.dataOut
  // TODO add an output mux for the weightsPort and connect to dram1 port
//  io.dram1.dataOut <> weightsPort.output
  io.dram1.dataOut.tieOff()
}
