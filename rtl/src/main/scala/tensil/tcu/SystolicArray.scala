/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import chisel3.util.{Counter, Decoupled, Queue, RegEnable, log2Ceil}
import tensil.util.decoupled.QueueWithReporting
import tensil.util.{zero, reportThroughput}
import tensil.util.Delay
import tensil.mem.OutQueue

class SystolicArray[T <: Data with Num[T]](
    val gen: T,
    val height: Int,
    val width: Int,
    debug: Boolean = false,
) extends Module {
  val io = IO(new Bundle {
    val control =
      Flipped(Decoupled(new Bundle {
        // false means run, true means load
        val load = Bool()
        // when loading: false means load from weight, true means load zeroes
        // when running: false means run from input, true means run with zeroes as input
        val zeroes = Bool()
      }))
    val input  = Flipped(Decoupled(Vec(width, gen)))  // input data
    val weight = Flipped(Decoupled(Vec(height, gen))) // weight parameters
    val output = Decoupled(Vec(height, gen))          // output data
    val ran = Decoupled(new Bundle {
      val zeroes = Bool()
    })
    val loaded = Decoupled(new Bundle {
      val zeroes = Bool()
    })
  })

  val arrayPropagationDelay = height + width - 1
  val array                 = Module(new InnerSystolicArray(gen, height, width))

  val input   = io.input
  val weight  = io.weight
  val control = io.control
  val output = Module(
    new Queue(
      array.io.output.cloneType,
      arrayPropagationDelay,
      flow = true,
    )
  )
  io.loaded.bits.zeroes := control.bits.zeroes
  io.loaded.valid := array.io.load
  val ran = Module(
    new Queue(chiselTypeOf(io.ran.bits), arrayPropagationDelay + 1, flow = true)
  )
  ran.io.enq.bits.zeroes := control.bits.zeroes
  io.ran.bits <> ran.io.deq.bits
  io.ran.valid := ran.io.deq.valid
  ran.io.deq.ready := io.ran.ready && output.io.deq.valid

  val runInput  = control.valid && !control.bits.load && !control.bits.zeroes
  val runZeroes = control.valid && !control.bits.load && control.bits.zeroes
  val loadWeight =
    control.valid && control.bits.load && !control.bits.zeroes
  val loadZeroes = control.valid && control.bits.load && control.bits.zeroes

  val running =
    ((runInput && input.valid && input.ready) || runZeroes) && output.io.deq.ready
  val loading = (loadWeight && weight.valid && weight.ready) || loadZeroes

  ran.io.enq.valid := running

  val arrayPropagationCountdown = RegInit(
    0.U(log2Ceil(arrayPropagationDelay).W)
  )
  when(running) {
    arrayPropagationCountdown := arrayPropagationDelay.U
  }.otherwise {
    when(arrayPropagationCountdown > 0.U) {
      arrayPropagationCountdown := arrayPropagationCountdown - 1.U
    }
  }

  // we need to gate loading on whether all the inputs have propagated through the array
  // TODO make it possible to start loading new weights once the inputs have passed the 1/2 way mark
  val inputDone = arrayPropagationCountdown === 0.U

  array.io.load := inputDone && loading
  when(control.bits.zeroes) {
    array.io.input <> zero(array.io.input)
    array.io.weight <> zero(array.io.weight)
  }.otherwise {
    array.io.input <> input.bits
    array.io.weight <> weight.bits
  }
  output.io.enq.bits <> array.io.output
  io.output <> output.io.deq

  input.ready := runInput && output.io.deq.ready
  weight.ready := loadWeight && inputDone
  control.ready := (!control.bits.load && (control.bits.zeroes || input.valid) && output.io.deq.ready) ||
    (control.bits.load && (control.bits.zeroes || weight.valid) && inputDone)

  // TODO set inputDone from an AND of these shift registers instead of count-down
  output.io.enq.valid := Delay(
    running,
    arrayPropagationDelay
  )

  if (debug) {
    when(input.valid && input.ready) {
      printf(p"SystolicArray: received input ${input.bits}\n")
    }
    when(weight.valid && weight.ready) {
      printf(p"SystolicArray: loaded weights ${weight.bits}\n")
    }
    when(loadZeroes && control.ready) {
      printf(p"SystolicArray: loaded weights zeroes\n")
    }
  }

}
