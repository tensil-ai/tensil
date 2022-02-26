package tf2rtl.zynq.tcu

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.util.Decoupled
import tf2rtl.{PlatformConfig, axi}
import tf2rtl.axi.{
  AXI4Stream,
  connectDownstreamInterface,
  connectUpstreamInterface,
}
import tf2rtl.mem.MemControl
import tf2rtl.tcu.{TCU, Sample}
import tf2rtl.tcu.instruction.Instruction
import tf2rtl.util.{WithLast, DecoupledHelper}
import tf2rtl.util.decoupled.Extend
import tf2rtl.Architecture

class AXIWrapperTCU[T <: Data with Num[T]](
    val axiConfig: axi.Config,
    val gen: T,
    val arch: Architecture,
)(implicit val platformConfig: PlatformConfig)
    extends MultiIOModule {
  val numScalarsPerWord: Int =
    (axiConfig.dataWidth / 8) / arch.dataType.sizeBytes

  val tcu = Module(
    new TCU(gen, arch)
  )
  // IO
  val instruction = IO(
    Flipped(Decoupled(new Instruction(tcu.instructionWidth)))
  )
  val status = IO(
    Decoupled(new WithLast(new Instruction(tcu.instructionWidth)))
  )
  val dram0  = IO(new axi.Master(axiConfig))
  val dram1  = IO(new axi.Master(axiConfig))
  val error  = IO(Output(Bool()))
  val sample = IO(Decoupled(new WithLast(new Sample)))

  tcu.io.instruction <> instruction
  status <> tcu.io.status
  error := tcu.io.error
  sample <> tcu.io.sample

  // mem boundary handling parameters
  val boundary = 1 << 12 // 4096
  val maxLen   = 1 << 8  // 256

  val dram0BoundarySplitter = Module(
    new axi.MemBoundarySplitter(axiConfig, boundary, maxLen)
  )
  dram0 <> dram0BoundarySplitter.io.out
  val dram1BoundarySplitter = Module(
    new axi.MemBoundarySplitter(axiConfig, boundary, maxLen)
  )
  dram1 <> dram1BoundarySplitter.io.out

  val dram0Converter = Module(
    new axi.Converter(
      axiConfig,
      gen,
      arch.arraySize,
      arch.dram0Depth,
      numScalarsPerWord = numScalarsPerWord
    )
  )
  dram0BoundarySplitter.io.in <> axi.Queue(dram0Converter.io.axi, 2)
  dram0Converter.io.mem <> tcu.io.dram0
  dram0Converter.io.addressOffset := tcu.io.config.dram0AddressOffset
  dram0Converter.io.cacheBehavior := tcu.io.config.dram0CacheBehaviour
  dram0Converter.io.timeout := tcu.io.timeout
  dram0Converter.io.tracepoint := tcu.io.tracepoint
  dram0Converter.io.programCounter := tcu.io.programCounter

  val dram1Converter = Module(
    new axi.Converter(
      axiConfig,
      gen,
      arch.arraySize,
      arch.dram1Depth,
      numScalarsPerWord = numScalarsPerWord
    )
  )
  dram1BoundarySplitter.io.in <> axi.Queue(dram1Converter.io.axi, 2)
  dram1Converter.io.mem <> tcu.io.dram1
  dram1Converter.io.addressOffset := tcu.io.config.dram1AddressOffset
  dram1Converter.io.cacheBehavior := tcu.io.config.dram1CacheBehaviour
  dram1Converter.io.timeout := tcu.io.timeout
  dram1Converter.io.tracepoint := tcu.io.tracepoint
  dram1Converter.io.programCounter := tcu.io.programCounter
}
