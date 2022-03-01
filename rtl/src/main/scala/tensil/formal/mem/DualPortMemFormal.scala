package tensil.formal.mem

import chisel3._
import chisel3.experimental.{verification => v}
import tensil.mem.DualPortMem
import tensil.mem.MemKind.XilinxBlockRAM
import tensil.formal._
import firrtl.MemKind
import tensil.PlatformConfig

class DualPortMemFormal extends Formal {
  implicit val platformConfig =
    PlatformConfig.default.copy(memKind = XilinxBlockRAM)
  val m = Module(new DualPortMem(SInt(2.W), 2))

  val io = IO(m.io.cloneType)
  io <> m.io

  for (port <- Array(m.io.portA, m.io.portB)) {
    val controlRead  = Node(port.control, filter = !port.control.bits.write)
    val controlWrite = Node(port.control, filter = port.control.bits.write)
    val input        = Node(port.input)
    val output       = Node(port.output)
    val wrote        = Node(port.wrote)

    depends(output, controlRead)
    depends(wrote, controlWrite)
    depends(wrote, input)
  }

  assertNoDeadlock()
}

object DualPortMemFormal extends App {
  tensil.util.emitToBuildDir(new DualPortMemFormal)
  Symbiyosys.emitConfig("DualPortMemFormal")
}
