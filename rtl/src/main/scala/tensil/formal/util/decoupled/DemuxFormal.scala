package tensil.formal.util.decoupled

import chisel3._
import tensil.util.decoupled
import tensil.util.emitToBuildDir
import tensil.formal.Formal
import tensil.formal.Symbiyosys

class DemuxFormal extends Formal {
  val m  = Module(new decoupled.Demux(Bool(), 2))
  val io = IO(m.io.cloneType)
  io <> m.io

  val sel0 = Node(m.io.sel, filter = m.io.sel.bits === 0.U)
  val sel1 = Node(m.io.sel, filter = m.io.sel.bits === 1.U)
  val in0  = Node(m.io.in, filter = m.io.sel.bits === 0.U)
  val in1  = Node(m.io.in, filter = m.io.sel.bits === 1.U)
  val out0 = Node(m.io.out(0))
  val out1 = Node(m.io.out(1))

  depends(out0, sel0)
  depends(out1, sel1)
  depends(out0, in0)
  depends(out1, in1)

  assertNoDeadlock()
}

object DemuxFormal extends App {
  emitToBuildDir(new DemuxFormal)
  Symbiyosys.emitConfig("DemuxFormal")
}
