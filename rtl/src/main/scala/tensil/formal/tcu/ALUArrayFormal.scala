package tensil.formal.tcu

import chisel3._
import chisel3.experimental.{verification => v}
import tensil.tcu._
import tensil.formal._
import tensil.tcu.simd._
import tensil.PlatformConfig
import tensil.mem.MemKind.XilinxBlockRAM

class ALUArrayFormal extends Formal {
  implicit val platformConfig =
    PlatformConfig.default.copy(memKind = XilinxBlockRAM)
  val gen = tensil.Architecture.mkWithDefaults(
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
  tensil.util.emitToBuildDir(new ALUArrayFormal)
  Symbiyosys.emitConfig("ALUArrayFormal")
}
