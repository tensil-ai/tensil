/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chisel3.util.{Decoupled, Queue}
import tensil.util

class VecAdder[T <: Data with Num[T]](gen: T, size: Int) extends Module {
  val io = IO(new Bundle {
    val left   = Flipped(Decoupled(Vec(size, gen)))
    val right  = Flipped(Decoupled(Vec(size, gen)))
    val output = Decoupled(Vec(size, gen))
  })

  val left = Queue(io.left, 1, pipe = true, flow = true)
  // val right = io.right
  val right = Queue(io.right, 1, pipe = true, flow = true)

  for (i <- 0 until size) {
    io.output.bits(i) := left.bits(i) + right.bits(i)
  }
  io.output.valid := left.valid && right.valid
  left.ready := io.output.ready && right.valid
  right.ready := io.output.ready && left.valid
}
