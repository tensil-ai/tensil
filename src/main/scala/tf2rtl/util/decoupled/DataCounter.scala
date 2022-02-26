package tf2rtl.util.decoupled

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.Decoupled
import chisel3.util.Queue

class DataCounter[T <: Data](gen: T, max: Int, numRequests: Int)
    extends Module {
  val io = IO(new Bundle {
    val in   = Flipped(Decoupled(gen))
    val out  = Decoupled(gen)
    val len  = Flipped(Decoupled(UInt(log2Ceil(max).W)))
    val last = Output(Bool())
  })

  val in  = io.in
  val len = io.len

  val counter = Counter(max)

  io.out.bits := in.bits
  io.out.valid := in.valid && len.valid
  in.ready := len.valid && io.out.ready

  when(counter.io.value.bits === len.bits) {
    io.last := in.valid && len.valid
    len.ready := in.valid && io.out.ready
    counter.io.resetValue := io.out.fire()
  }.otherwise {
    len.ready := false.B
    io.last := false.B
    counter.io.value.ready := io.out.fire()
  }
}
