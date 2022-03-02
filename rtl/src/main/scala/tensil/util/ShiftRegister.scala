/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util

import chisel3._

class ShiftRegister[T <: Data](val gen: T, val n: Int) {
  val srGen = Vec(n, gen)
  val sr    = RegInit(zero(srGen))

  for (i <- 1 until n) {
    sr(i) := sr(i - 1)
  }

  def apply(i: Int): T = sr(i)

  def enq: T = sr(0)
  def deq: T = sr(n - 1)
}

object ShiftRegister {
  def apply[T <: Data](wire: T, n: Int): ShiftRegister[T] = {
    val sr = new ShiftRegister(chiselTypeOf(wire), n)
    sr.enq := wire
    sr
  }
}

object Delay {
  def apply[T <: Data](wire: T, n: Int): T = {
    if (n == 0) {
      wire
    } else {
      ShiftRegister(wire, n).deq
    }
  }
}

class ChiselShiftRegisterModule(val n: Int) extends Module {
  val io = IO(new Bundle {
    val enq = Input(UInt(32.W))
    val deq = Output(UInt(32.W))
  })

  io.deq := chisel3.util.ShiftRegister(io.enq, n)
  //  printf(p"enq = ${io.enq} deq = ${io.deq}\n")
}
