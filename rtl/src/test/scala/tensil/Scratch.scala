/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.experimental.BundleLiterals._
import chisel3.util.Decoupled
import chiseltest._
import tensil.util.decoupled.Counter
import chisel3.util.Queue

class Scratch extends Module {
  val inWidth  = 64
  val outWidth = 72

  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(UInt(inWidth.W)))
    val out = Decoupled(UInt(outWidth.W))
  })

  // assume output width is greater than input width for now
  if (outWidth > inWidth) {
    val gcd = util.greatestCommonDivisor(inWidth, outWidth)
    val lcm = util.leastCommonMultiple(inWidth, outWidth)
    val arr = RegInit(VecInit(Array.fill(lcm)(0.U(gcd.W))))
    arr.asUInt(0, 10)
  } else if (outWidth < inWidth) {
    io.in.nodeq()
    io.out.noenq()
  } else {
    // they are equal
    io.in.nodeq()
    io.out.noenq()
  }
}

class ScratchSpec extends UnitSpec {
  behavior of "Scratch"

  it should "work" in {
    test(new Scratch) { m => }
  }
}
