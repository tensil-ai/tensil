package tf2rtl.formal.util.decoupled

import chisel3._
import tf2rtl.formal.Formal
import tf2rtl.util.decoupled.VecAdder
import tf2rtl.util.emitToBuildDir
import tf2rtl.formal.Symbiyosys

class VecAdderFormal extends Formal {
  val m  = Module(new VecAdder(SInt(2.W), 2))
  val io = IO(m.io.cloneType)
  io <> m.io

  val left   = Node(m.io.left)
  val right  = Node(m.io.right)
  val output = Node(m.io.output)

  depends(output, left)
  depends(output, right)

  assertNoDeadlock()
}

object VecAdderFormal extends App {
  emitToBuildDir(new VecAdderFormal)
  Symbiyosys.emitConfig("VecAdderFormal")
}
