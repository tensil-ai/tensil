/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

import chisel3._
import chisel3.util.Decoupled
import tensil.util.decoupled.Counter
import tensil.util.decoupled.QueueWithReporting
import chisel3.util.Queue

class SizeHandler[
    T <: Bundle with Size,
    S <: Bundle
](inGen: T, outGen: S, depth: Long, debug: Boolean = false, name: String = "")
    extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(inGen))
    val out = Decoupled(outGen)
  })

  val sizeCounter = Counter(depth)
  val in          = io.in

  io.out.valid := in.valid
  for ((name, w) <- in.bits.elements) {
    if (io.out.bits.elements.contains(name)) {
      io.out.bits.elements(name) := w
    }
  }

  val fire = in.valid && io.out.ready

  when(sizeCounter.io.value.bits === in.bits.size) {
    in.ready := io.out.ready
    sizeCounter.io.resetValue := fire
  }.otherwise {
    in.ready := false.B
    sizeCounter.io.value.ready := fire
  }

  if (debug) {
    when(sizeCounter.io.value.ready) {
      printf(
        p"SizeHandler.in = $in sizeCounter =${sizeCounter.io.value.bits}\n"
      )
    }
  }
}
