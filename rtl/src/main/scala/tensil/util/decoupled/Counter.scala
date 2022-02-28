package tensil.util.decoupled

import chisel3._
import chisel3.util.{Decoupled, log2Ceil}

class Counter(val n: Long) extends Module {
  val width = log2Ceil(n)
  val io = IO(new Bundle {
    val value      = Decoupled(UInt(width.W))
    val resetValue = Input(Bool())
  })

  val value = RegInit(0.U(width.W))

  io.value.bits := value
  io.value.valid := !io.resetValue
  when(io.value.ready) {
    when(value === (n - 1).U) {
      value := 0.U
    }.otherwise {
      value := value + 1.U
    }
  }
  when(io.resetValue) {
    value := 0.U
  }
}

object Counter {
  def apply(n: Long): Counter = {
    val m = Module(new Counter(n))
    m.io.resetValue := false.B
    m.io.value.ready := false.B
    m
  }
}
