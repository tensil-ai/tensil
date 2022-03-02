/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chisel3.util.Decoupled

// adds every n inputs together
class Adder(n: Int = 2) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(UInt(32.W)))
    val out = Decoupled(UInt(32.W))
  })

  val transmission = Module(new Transmission(32, n * 32))
  transmission.io.in <> io.in
  transmission.io.error := false.B
  val data = transmission.io.out.bits.asTypeOf(Vec(n, UInt(32.W)))

  io.out.bits := data.reduce(_ + _)
  io.out.valid := transmission.io.out.valid
  transmission.io.out.ready := io.out.ready
}
