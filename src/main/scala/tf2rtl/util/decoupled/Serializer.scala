package tf2rtl.util.decoupled

import chisel3._
import chisel3.util.Decoupled
import tf2rtl.util.zero

// TODO Make this a ser-des based on a shift register. Note: this is probably the
//  ideal place for a clock domain crossing since the two sides inherently have
//  different data rates (i.e. the serial side would ideally be clocked N times
//  faster than the parallel side in order to achieve balanced data flow (where
//  N is the number of parallel ports)).
class Serializer[T <: Data](gen: T, n: Int) extends Module {
  val io = IO(new Bundle {
    val in    = Flipped(Decoupled(Vec(n, gen)))
    val out   = Decoupled(gen)
    val error = Input(Bool())
  })
  dontTouch(io.error)

  val bits  = RegInit(VecInit(Seq.fill(n)(zero(gen))))
  val valid = RegInit(false.B)

  val (ctr, wrap) = chisel3.util.Counter(valid && io.out.ready, n)

  io.out.valid := valid
  io.out.bits := bits(ctr)
  io.in.ready := !valid || wrap

  when(io.in.ready) {
    when(io.in.valid) {
      bits := io.in.bits.asTypeOf(bits)
      valid := true.B
    }.otherwise {
      valid := false.B
    }
  }
}
