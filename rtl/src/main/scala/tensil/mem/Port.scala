/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

import chisel3._
import chisel3.util.Decoupled

class Port[T <: Data](gen: T, val depth: Long) extends Bundle {
  val control     = Flipped(Decoupled(new MemControl(depth)))
  val input       = Flipped(Decoupled(gen))
  val output      = Decoupled(gen)
  val wrote       = Decoupled(Bool())
  val status      = Decoupled(new MemControl(depth))
  val inputStatus = Decoupled(gen)
}

class PortWithStride[T <: Data](gen: T, val depth: Long, strideDepth: Int)
    extends Bundle {
  val control     = Flipped(Decoupled(new MemControlWithStride(depth, strideDepth)))
  val input       = Flipped(Decoupled(gen))
  val output      = Decoupled(gen)
  val wrote       = Decoupled(Bool())
  val status      = Decoupled(new MemControl(depth))
  val inputStatus = Decoupled(gen)
}
