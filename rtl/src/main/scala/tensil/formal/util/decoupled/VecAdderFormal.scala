package tensil.formal.util.decoupled

import chisel3._
import tensil.formal.Formal
import tensil.util.decoupled.VecAdder
import tensil.util.emitToBuildDir
import tensil.formal.Symbiyosys

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
