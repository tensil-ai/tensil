package tensil.util.decoupled

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.util.{Decoupled, DecoupledIO}
import tensil.util.zero

class Extend[T <: Data](gen: T, desired: T) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(gen))
    val out = Decoupled(desired)
  })

  io.out.bits := io.in.bits
  io.out.valid := io.in.valid
  io.in.ready := io.out.ready
}

object Extend extends App {
  val desired = FixedPoint(18.W, 10.BP)
  val gen     = FixedPoint(32.W, 16.BP)

  tensil.util.Driver() { () =>
    new Extend(gen, desired)
  }

  def apply[T <: Data](
      f: DecoupledIO[T],
      t: T
  ): DecoupledIO[T] = {
    val m = Module(new Extend(chiselTypeOf(f.bits), t))
    m.io.in <> f
    m.io.out
  }

  def apply[T <: Data](a: T, b: T): T = {
    val w = Wire(b)
    w := a
    w
  }
}
