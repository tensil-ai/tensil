package tf2rtl.util

import chisel3._
import chisel3.util.{Decoupled, Queue}

object Demux {
  def apply[T <: Data](cond: Bool, con: T, alt: T): T = {
    val input = Wire(con.cloneType)
    when(cond) {
      con := input
    }.otherwise {
      alt := input
    }
    input
  }
}
