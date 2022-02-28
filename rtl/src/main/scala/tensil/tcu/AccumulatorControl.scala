package tensil.tcu

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO, Queue, log2Ceil}
import tensil.{PlatformConfig, util}
import tensil.util.{DecoupledHelper, Delay, allReady, enqueue}
import tensil.util.decoupled.VecAdder
import tensil.util.decoupled
import tensil.mem.{Mem, MemControl}

class AccumulatorControl(val depth: Long) extends Bundle {
  val address    = UInt(log2Ceil(depth).W)
  val accumulate = Bool()
  val write      = Bool()
}

object AccumulatorControl {
  def apply(depth: Int): DecoupledIO[AccumulatorControl] = {
    val control = Wire(Decoupled(new AccumulatorControl(depth)))
    control.valid := false.B
    control.bits.address := 0.U
    control.bits.write := false.B
    control.bits.accumulate := false.B
    control
  }
}
