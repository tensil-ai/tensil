/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chisel3.util.Decoupled
import tensil.util.zero

class Splitter[T <: Data](n: Int, gen: T) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(gen))
    val out = Vec(n, Decoupled(gen))
  })
  val increment  = WireInit(false.B)
  val (state, _) = chisel3.util.Counter(increment, n)
  increment := io.out(state).ready && io.out(state).valid

  for (i <- 0 until n) {
    io.out(i).valid := false.B
    io.out(i).bits := zero(gen)
  }

  io.out(state) <> io.in
}
