package tf2rtl.formal.mem

import chisel3._
import chisel3.experimental.{verification => v}
import tf2rtl.mem.Mem
import tf2rtl.mem.MemKind.XilinxBlockRAM
import tf2rtl.formal._
import firrtl.MemKind
import tf2rtl.PlatformConfig

class MemFormal extends Formal {
  implicit val platformConfig =
    PlatformConfig.default.copy(memKind = XilinxBlockRAM)
  val m = Module(new Mem(SInt(2.W), 2, inQueueFlow = true))

  val io = IO(m.io.cloneType)
  io <> m.io

  val controlRead  = Node(m.io.control, filter = !m.io.control.bits.write)
  val controlWrite = Node(m.io.control, filter = m.io.control.bits.write)
  val input        = Node(m.io.input)
  val output       = Node(m.io.output)
  val wrote        = Node(m.io.wrote)

  depends(output, controlRead)
  depends(wrote, controlWrite)
  depends(wrote, input)

  assertNoDeadlock()
}

object MemFormal extends App {
  tf2rtl.util.emitToBuildDir(new MemFormal)
  Symbiyosys.emitConfig("MemFormal")
}
