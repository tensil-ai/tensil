package tf2rtl.formal.util.decoupled

import chisel3._
import tf2rtl.util.decoupled
import tf2rtl.util.emitToBuildDir
import tf2rtl.formal.Formal
import tf2rtl.formal.Symbiyosys

class MuxFormal extends Formal {
  val m  = Module(new decoupled.Mux(Bool(), 2))
  val io = IO(m.io.cloneType)
  io <> m.io

  val sel  = Node(m.io.sel)
  val in0  = Node(m.io.in(0))
  val in1  = Node(m.io.in(1))
  val out  = Node(m.io.out)
  val out0 = Node(m.io.out, filter = m.io.sel.bits === 0.U)
  val out1 = Node(m.io.out, filter = m.io.sel.bits === 1.U)

  depends(out, sel)
  depends(out0, in0)
  depends(out1, in1)

  assertNoDeadlock()
}

object MuxFormal extends App {
  emitToBuildDir(new MuxFormal)
  Symbiyosys.emitConfig("MuxFormal")
}
