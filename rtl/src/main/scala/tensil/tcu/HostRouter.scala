package tensil.tcu

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.log2Ceil
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import tensil.Architecture
import tensil.mem.Size
import tensil.util.decoupled

class HostRouter[T <: Data](val gen: T, val arch: Architecture) extends Module {
  val io = IO(new Bundle {
    val control =
      Flipped(Decoupled(new HostDataFlowControlWithSize(arch.localDepth)))
    val dram0 = new Bundle {
      val dataIn  = Flipped(Decoupled(gen))
      val dataOut = Decoupled(gen)
    }
    val dram1 = new Bundle {
      val dataIn  = Flipped(Decoupled(gen))
      val dataOut = Decoupled(gen)
    }
    val mem = new Bundle {
      val output = Flipped(Decoupled(gen))
      val input  = Decoupled(gen)
    }
  })

  val control = io.control

  val dataIn = decoupled.Mux(io.dram0.dataIn, io.dram1.dataIn, io.mem.input)
  val dataOut =
    decoupled.Demux(io.mem.output, io.dram0.dataOut, io.dram1.dataOut)

  val isDataIn  = HostDataFlowControl.isDataIn(control.bits.kind)
  val isDataOut = HostDataFlowControl.isDataOut(control.bits.kind)

  control.ready := (isDataIn && dataIn.ready) || (isDataOut && dataOut.ready)

  dataIn.valid := control.valid && isDataIn
  dataIn.bits := control.bits.kind(1)

  dataOut.valid := control.valid && isDataOut
  dataOut.bits := control.bits.kind(1)
}

object HostDataFlowControl {
  val In0  = 0
  val Out0 = 1
  val In1  = 2
  val Out1 = 3

  def isDataIn(kind: UInt): Bool = {
    kind === In0.U || kind === In1.U
  }

  def isDataOut(kind: UInt): Bool = {
    kind === Out0.U || kind === Out1.U
  }

  def apply(kind: UInt): HostDataFlowControl = {
    if (kind.isLit()) {
      new HostDataFlowControl().Lit(
        _.kind -> kind,
      )
    } else {
      val w = Wire(new HostDataFlowControl())
      w.kind := kind
      w
    }
  }
}

class HostDataFlowControl extends Bundle {
  val kind = UInt(2.W)
}
class HostDataFlowControlWithSize(val depth: Long)
    extends HostDataFlowControl
    with Size {
  val size = UInt(log2Ceil(depth).W)
}

object HostDataFlowControlWithSize {
  def apply(
      depth: Long
  )(kind: UInt, size: UInt): HostDataFlowControlWithSize = {
    if (kind.isLit() && size.isLit()) {
      new HostDataFlowControlWithSize(depth).Lit(_.kind -> kind, _.size -> size)
    } else {
      val w = Wire(new HostDataFlowControlWithSize(depth))
      w.kind := kind
      w.size := size
      w
    }
  }
}
