/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chisel3.util.{Decoupled, log2Ceil}

class CountBy(val n: Long) extends Module {
  val width = log2Ceil(n)
  val io = IO(new Bundle {
    val value      = Decoupled(UInt(width.W))
    val step       = Input(UInt(width.W))
    val resetValue = Input(Bool())
  })

  val value = RegInit(0.U(width.W))

  io.value.bits := value
  io.value.valid := !io.resetValue
  when(io.value.ready) {
    when(value >= (n.U - io.step)) {
      value := 0.U
    }.otherwise {
      value := value + io.step
    }
  }
  when(io.resetValue) {
    value := 0.U
  }
}

object CountBy {
  def apply(n: Long, step: UInt): CountBy = {
    val m = Module(new CountBy(n))
    m.io.step := step
    m.io.resetValue := false.B
    m.io.value.ready := false.B
    m
  }
}
