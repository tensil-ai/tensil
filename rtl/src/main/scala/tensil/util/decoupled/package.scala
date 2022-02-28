package tensil.util

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO}

package object decoupled {
  def asTypeOf[S <: Data, T <: Data](
      d: DecoupledIO[S],
      t: T
  ): DecoupledIO[T] = {
    val w = Wire(Decoupled(t))
    w.bits := d.bits.asTypeOf(t)
    w.valid := d.valid
    d.ready := w.ready
    w
  }
}
