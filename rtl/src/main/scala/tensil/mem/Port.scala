/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

import chisel3._
import chisel3.util.Decoupled

class Port[T <: Data](val gen: T, val depth: Long) extends Bundle {
  val control     = Flipped(Decoupled(new MemControl(depth)))
  val input       = Flipped(Decoupled(gen))
  val output      = Decoupled(gen)
  val wrote       = Decoupled(Bool())
  val status      = Decoupled(new MemControl(depth))
  val inputStatus = Decoupled(gen)

  override def cloneType =
    (new Port(gen, depth)).asInstanceOf[this.type]
}
