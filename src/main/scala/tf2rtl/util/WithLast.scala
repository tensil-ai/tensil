package tf2rtl.util

import chisel3._

class WithLast[T <: Data](gen: T) extends Bundle {
  val last = Output(Bool())
  val bits = Output(gen)

  override def cloneType: this.type = new WithLast(gen).asInstanceOf[this.type]
}
