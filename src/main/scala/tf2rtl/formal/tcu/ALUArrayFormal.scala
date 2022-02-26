package tf2rtl.formal.tcu

import chisel3._
import chisel3.experimental.{verification => v}
import tf2rtl.tcu._
import tf2rtl.formal._
import tf2rtl.tcu.simd._
import tf2rtl.PlatformConfig
import tf2rtl.mem.MemKind.XilinxBlockRAM

class ALUArrayFormal extends Formal {
  implicit val platformConfig =
    PlatformConfig.default.copy(memKind = XilinxBlockRAM)
  val gen = tf2rtl.Architecture.mkWithDefaults(
    arraySize = 2,
    localDepth = 8,
    accumulatorDepth = 8
  )
  val m  = Module(new ALUArray(SInt(2.W), gen))
  val io = IO(m.io.cloneType)
  io <> m.io

  val input       = Node(m.io.input)
  val instruction = Node(m.io.instruction)
  val output      = Node(m.io.output)

  depends(output, instruction)
  depends(output, input)

  assertNoDeadlock()
}

object ALUArrayFormal extends App {
  tf2rtl.util.emitToBuildDir(new ALUArrayFormal)
  Symbiyosys.emitConfig("ALUArrayFormal")
}
