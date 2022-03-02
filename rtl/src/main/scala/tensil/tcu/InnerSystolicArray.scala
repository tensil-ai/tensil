/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3.{
  Bool,
  Bundle,
  Data,
  Input,
  Module,
  Num,
  Output,
  RegInit,
  Vec,
  when
}
import tensil.util.{Delay, zero}

class InnerSystolicArray[T <: Data with Num[T]](
    val gen: T,
    val height: Int,
    val width: Int
) extends Module {
  val io = IO(new Bundle {
    val load   = Input(Bool())            // signal determines when weights are loaded in
    val input  = Input(Vec(width, gen))   // input data
    val weight = Input(Vec(height, gen))  // weight parameters
    val output = Output(Vec(height, gen)) // output data
  })

  val mac =
    for (i <- 0 until height)
      yield for (j <- 0 until width) yield Module(new MAC(gen))

  val bias = for (i <- 0 until height) yield {
    val b = RegInit(zero(gen))
    when(io.load) {
      b := io.weight(i)
    }
    b
  }

  // connect first row
  for (j <- 0 until width) {
    // inputs attach here
    // TODO smaller array <-> faster clock rate sometimes?
    mac(0)(j).io.mulInput := Delay(io.input(j), j)
    if (j > 0) {
      mac(0)(j).io.addInput := mac(0)(j - 1).io.output
    }
    mac(0)(j).io.load := io.load
  }

  // connect first column
  for (i <- 0 until height) {
    mac(i)(0).io.addInput := bias(i)
    if (i > 0) {
      mac(i)(0).io.mulInput := mac(i - 1)(0).io.passthrough
      mac(i)(0).io.load := io.load
    }
  }

  // connect the body
  for (i <- 1 until height) {
    for (j <- 1 until width) {
      mac(i)(j).io.mulInput := mac(i - 1)(j).io.passthrough
      mac(i)(j).io.addInput := mac(i)(j - 1).io.output
      mac(i)(j).io.load := io.load
    }
  }

  // outputs attach here
  for (i <- 0 until height) {
    io.output(i) := Delay(mac(i)(width - 1).io.output, height - (i + 1))
  }
}
