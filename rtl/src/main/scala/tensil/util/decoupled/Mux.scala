/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util.{Decoupled, DecoupledIO, Queue}
import tensil.mem.Size

class MuxSelWithSize(n: Int, val depth: Long) extends MuxSel(n) with Size {
  val size = UInt(util.log2Ceil(depth).W)
}

object MuxSelWithSize {
  def apply(n: Int, depth: Long, sel: UInt, size: UInt): MuxSelWithSize =
    if (sel.isLit() && size.isLit()) {
      new MuxSelWithSize(n, depth).Lit(_.sel -> sel, _.size -> size)
    } else {
      val w = Wire(new MuxSelWithSize(n, depth))
      w.sel := sel
      w.size := size
      w
    }
}

class MuxSel(val n: Int) extends Bundle {
  val sel = UInt(util.log2Ceil(n).W)
}

object MuxSel {
  def apply(n: Int, sel: UInt): MuxSel = new MuxSel(n).Lit(_.sel -> sel)
}

class Mux[T <: Data](
    val gen: T,
    val n: Int,
    controlQueueSize: Int = 2,
    name: String = ""
) extends Module {
  val io = IO(new Bundle {
    val in  = Vec(n, Flipped(Decoupled(gen)))
    val sel = Flipped(Decoupled(UInt(util.log2Ceil(n).W)))
    val out = Decoupled(gen)
  })

  val in = VecInit(
    // for (i <- 0 until n) yield QueueWithReporting(io.in(i), 1 << 1, name=name) // 5
    for (i <- 0 until n)
      // yield QueueWithReporting(io.in(i), 1 << 5, name = name) // 5
      // yield Queue(io.in(i), 2, flow = true)
      yield io.in(i)
  )
  for (i <- 0 until n) {
    in(i.U).ready := false.B
  }
  // val sel = QueueWithReporting(io.sel, controlQueueSize, name = name)
  // val sel = Queue(io.sel, 2, flow = true)
  val sel = io.sel

  val inS = in(sel.bits)
  io.out.bits := inS.bits
  io.out.valid := sel.valid && inS.valid
  sel.ready := inS.valid && io.out.ready
  inS.ready := sel.valid && io.out.ready
}

object Mux {
  def apply[T <: Data](
      in0: DecoupledIO[T],
      in1: DecoupledIO[T],
      out: DecoupledIO[T],
      controlQueueSize: Int = 2,
      name: String = ""
  ): DecoupledIO[UInt] = {
    val mux = Module(
      new Mux(
        chiselTypeOf(in0.bits),
        2,
        controlQueueSize = controlQueueSize,
        name = name
      )
    )
    mux.io.in(0) <> in0
    mux.io.in(1) <> in1
    out <> mux.io.out
    mux.io.sel
  }
}
