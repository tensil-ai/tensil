/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chisel3.util.Decoupled
import tensil.util.zero

class Deserializer[T <: Data](gen: T, n: Int) extends Module {
  val io = IO(new Bundle {
    val in    = Flipped(Decoupled(gen))
    val out   = Decoupled(Vec(n, gen))
    val error = Input(Bool())
  })
  dontTouch(io.error)

  val bits  = RegInit(VecInit(Seq.fill(n)(zero(gen))))
  val valid = RegInit(false.B)

  val (ctr, wrap) =
    chisel3.util.Counter(io.in.ready && io.in.valid, n)

  io.out.valid := valid
  io.out.bits := bits.asTypeOf(io.out.bits)
  // when valid is true, wait until out.ready goes true
  io.in.ready := !valid || io.out.ready

  when(io.in.ready) {
    when(io.in.valid) {
      bits(ctr) := io.in.bits
      valid := wrap
    }
  }
  when(io.out.ready) {
    when(valid) {
      valid := false.B
    }
  }
}
