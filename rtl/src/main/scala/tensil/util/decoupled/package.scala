/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO}
import tensil.mem.SizeHandler
import tensil.mem.OutQueue

package object decoupled {
  def asTypeOf[S <: Data, T <: Data](
      d: DecoupledIO[S],
      t: T
  ): DecoupledIO[T] = {
    val w = Wire(Decoupled(t))
    w.bits := d.bits.asTypeOf(t)
    w.valid := d.valid
    d.ready := w.ready
    w
  }

  def makeSizeHandler(
      n: Int,
      name: String,
      muxSel: DecoupledIO[UInt],
      depth: Long,
      size: UInt,
      bufferSize: Int = 1,
  ): (DecoupledIO[MuxSelWithSize], UInt => MuxSelWithSize) = {
    val inGen  = new MuxSelWithSize(n, depth)
    val outGen = new MuxSel(n)
    val sizeHandler = Module(
      new SizeHandler(inGen, outGen, depth, name = name)
    )
    val muxSelWithSize =
      OutQueue(sizeHandler.io.in, bufferSize, pipe = true, flow = true)
    muxSel.bits := sizeHandler.io.out.bits.sel
    muxSel.valid := sizeHandler.io.out.valid
    sizeHandler.io.out.ready := muxSel.ready

    def muxSelLit(sel: UInt): MuxSelWithSize =
      MuxSelWithSize(n, depth, sel, size)
    (muxSelWithSize, muxSelLit)
  }
}
