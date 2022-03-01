package tensil.util.decoupled

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO}
import tensil.util.zero

// Value of signal delayed N decoupled transfers ago (instead of N cycles)
// Passes through the actual value instantly and transparently. The delayed
// value is available on io.delayed
class Delay[T <: Data](gen: T, n: Int) extends Module {
  val io = IO(new Bundle {
    val in      = Flipped(Decoupled(gen))
    val out     = Decoupled(gen)
    val delayed = Output(gen)
  })
  val reg = RegInit(VecInit(Seq.fill(n)(zero(gen))))
  io.delayed := reg(n - 1)
  io.out <> io.in

  when(io.in.valid && io.out.ready) {
    reg(0) := io.in.bits
    for (i <- 1 until n) {
      reg(i) := reg(i - 1)
    }
  }
}

object Delay {
  def apply[T <: Data](signal: DecoupledIO[T], n: Int): (DecoupledIO[T], T) = {
    val d = Module(new Delay(chiselTypeOf(signal.bits), n))
    d.io.in <> signal
    (d.io.out, d.io.delayed)
  }
}
