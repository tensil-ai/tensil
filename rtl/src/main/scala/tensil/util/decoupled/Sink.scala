package tensil.util.decoupled

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO}

class Sink[T <: Data](val gen: T) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(gen))
  })

  io.in.ready := true.B
}

object Sink {
  def apply[T <: Data](gen: DecoupledIO[T]): DecoupledIO[T] = {
    val sink = Module(new Sink(chiselTypeOf(gen.bits)))
    sink.io.in <> gen
    sink.io.in
  }
}
