package tensil.formal

import chisel3._
import chisel3.experimental.{verification => v}
import tensil.tcu._
import chisel3.stage.ChiselGeneratorAnnotation

class Counter(n: Int) extends Module {
  private val _width = chisel3.util.log2Ceil(n)
  val io = IO(new Bundle {
    val value = Output(UInt(_width.W))
    val wrap  = Output(Bool())
    val incr  = Input(Bool())
    val zero  = Input(Bool())
  })

  val value = RegInit(0.U(_width.W))
  io.value := value
  io.wrap := value === (n - 1).U

  when(io.zero) {
    value := 0.U
  }.otherwise {
    when(io.incr) {
      value := value + 1.U
    }
  }
}

object Counter {
  def apply(n: Int, incr: Bool = true.B, zero: Bool = false.B): Counter = {
    val c = Module(new Counter(n))
    c.io.incr := incr
    c.io.zero := zero
    c
  }
}
