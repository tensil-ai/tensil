/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.Queue
import tensil.util.zero
import tensil.util.decoupled.QueueWithReporting

class StrideHandler[
    T <: Bundle with Address with Size with Stride with Reverse,
    S <: Bundle with Address with Size
](inGen: T, outGen: S, depth: Long, strideDepth: Int, name: String = "")
    extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(inGen.cloneType))
    val out = Decoupled(outGen.cloneType)
  })

  // TODO
  //  what happens when size = 0 but stride > 0?
  //  what happens when size * stride > depth?

  val in = io.in
  val handler = Module(
    new SizeAndStrideHandler(
      inGen,
      outGen,
      depth,
      strideDepth,
      inputQueue = false,
      name = name
    )
  )
  handler.io.in.valid := false.B
  handler.io.in.bits := zero(handler.io.in.bits)
  handler.io.out.ready := false.B

  when(in.bits.stride === 0.U) {
    for ((name, w) <- in.bits.elements) {
      if (io.out.bits.elements.contains(name)) {
        io.out.bits.elements(name) := w
      }
    }
    io.out.bits.address := in.bits.address
    io.out.bits.size := in.bits.size
    in.ready := io.out.ready
    io.out.valid := in.valid
  }.otherwise {
    handler.io.in <> in
    for ((name, w) <- handler.io.out.bits.elements) {
      if (io.out.bits.elements.contains(name)) {
        io.out.bits.elements(name) := w
      }
    }
    io.out.bits.address := handler.io.out.bits.address
    io.out.bits.size := handler.io.out.bits.size
    handler.io.out.ready := io.out.ready
    io.out.valid := handler.io.out.valid
  }
}
