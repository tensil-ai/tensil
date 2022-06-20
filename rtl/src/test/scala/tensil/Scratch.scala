/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

import chisel3._
import chisel3.util.Cat
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
    val gcd             = util.greatestCommonDivisor(inWidth, outWidth)
    val lcm             = util.leastCommonMultiple(inWidth, outWidth)
    val numBlocks       = lcm / gcd
    val blocksPerInput  = inWidth / gcd
    val blocksPerOutput = outWidth / gcd
    val arr             = RegInit(VecInit(Array.fill(numBlocks)(0.U(gcd.W))))

    val nextIn  = RegInit(0.U(log2Ceil(numBlocks).W))
    val nextOut = RegInit(0.U(log2Ceil(numBlocks).W))

    when(io.in.fire) {
      for (i <- 0 until blocksPerInput) {
        arr(i.U + nextIn) := io.in.bits(i * gcd, (i + 1) * gcd)
      }
      counter(nextIn, blocksPerInput, lcm)
    }

    when(io.out.fire) {
      io.out.bits := Cat(arr.slice(nextOut, nextOut + blocksPerOutput.U))
      counter(nextOut, blocksPerOutput, lcm)
    }

    // TODO need to handle wrapping around the ends
    io.in.ready := nextIn < nextOut
    io.out.valid := nextIn > nextOut && nextIn < (nextOut + blocksPerOutput.U)

  } else if (outWidth < inWidth) {
    io.in.nodeq()
    io.out.noenq()
  } else {
    // they are equal
    io.in.nodeq()
    io.out.noenq()
  }

  def counter(reg: UInt, step: Int, max: Int): Unit = {
    when(reg === (max - step).U) {
      reg := 0.U
    }.otherwise {
      reg := reg + step.U
    }
  }
}

class ScratchSpec extends FunUnitSpec {
  describe("Scratch") {
    it("should work") {
      decoupledTest(new Scratch) { m =>
        thread("in") {
          m.io.in.enqueue("h0102030405060708".U)
          m.io.in.enqueue("h090a0b0c0d0e0f10".U)
          m.io.in.enqueue("h1112131415161718".U)
        }
        thread("out") {
          m.io.out.expectDequeue("h010203040506070809".U)
          m.io.out.expectDequeue("h0a0b0c0d0e0f101112".U)
        }
      }
    }
  }
}
