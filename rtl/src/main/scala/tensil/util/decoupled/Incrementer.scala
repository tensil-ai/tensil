/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chisel3.util.{Decoupled, Queue}

class Incrementer(width: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(UInt(width.W)))
    val out = Decoupled(UInt(width.W))
  })
  val q = Queue(io.in, 1)
  io.out.valid := q.valid
  q.ready := io.out.ready
  io.out.bits := q.bits + 1.U
}
