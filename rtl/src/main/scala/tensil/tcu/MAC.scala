/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import tensil.util

class MAC[T <: Data with Num[T]](val gen: T) extends Module {
  val io = IO(new Bundle {
    val load        = Input(Bool())
    val mulInput    = Input(gen)
    val addInput    = Input(gen)
    val output      = Output(gen)
    val passthrough = Output(gen)
  })

  val weight      = RegInit(util.zero(gen))
  val passthrough = RegInit(util.zero(gen))
  val output      = RegInit(util.zero(gen))

  io.passthrough := passthrough
  passthrough := io.mulInput

  when(io.load) {
    weight := io.addInput
    io.output := weight
  }.otherwise {
    output := util.mac(gen, io.mulInput, weight, io.addInput)
    io.output := output
  }
}
