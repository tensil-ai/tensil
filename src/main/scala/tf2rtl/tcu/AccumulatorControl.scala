package tf2rtl.tcu

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO, Queue, log2Ceil}
import tf2rtl.{PlatformConfig, util}
import tf2rtl.util.{DecoupledHelper, Delay, allReady, enqueue}
import tf2rtl.util.decoupled.VecAdder
import tf2rtl.util.decoupled
import tf2rtl.mem.{Mem, MemControl}

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
