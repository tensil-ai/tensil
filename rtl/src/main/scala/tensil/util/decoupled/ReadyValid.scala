/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.DecoupledIO
import tensil.util.isOutput

class ReadyValid extends Bundle {
  val ready = Input(Bool())
  val valid = Output(Bool())

  def toDecoupled[T <: Data](bits: T): DecoupledIO[T] = {
    val w = Wire(Decoupled(chiselTypeOf(bits)))
    w.bits := bits
    w.valid := valid
    ready := w.ready
    w
  }

  def noenq(): Unit = {
    valid := false.B
  }

  def nodeq(): Unit = {
    ready := false.B
  }
}

object ReadyValid {
  def apply(d: DecoupledIO[Data]): ReadyValid = {
    val w = Wire(new ReadyValid)
    d.ready := w.ready
    w.valid := d.valid
    w
  }
}
