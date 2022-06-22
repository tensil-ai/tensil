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

class Scratch extends Module { val io = IO(new Bundle {}) }
class WidthConverter(inWidth: Int, outWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(UInt(inWidth.W)))
    val out = Decoupled(UInt(outWidth.W))
  })

  if (outWidth == inWidth) {
    io.out <> io.in
  } else {
    val gcd             = util.greatestCommonDivisor(inWidth, outWidth)
    val lcm             = util.leastCommonMultiple(inWidth, outWidth)
    val numBlocks       = lcm / gcd
    val blocksPerInput  = inWidth / gcd
    val blocksPerOutput = outWidth / gcd
    val arr             = RegInit(VecInit(Array.fill(numBlocks)(0.U(gcd.W))))

    val enqPtr     = RegInit(0.U(log2Ceil(numBlocks).W))
    val deqPtr     = RegInit(0.U(log2Ceil(numBlocks).W))
    val maybeFull  = RegInit(false.B)
    val ptrMatch   = enqPtr === deqPtr
    val enqPtrNext = enqPtr +& blocksPerInput.U
    val deqPtrNext = deqPtr +& blocksPerOutput.U
    val full =
      Mux(
        ptrMatch,
        maybeFull,
        Mux(
          enqPtr < deqPtr,
          enqPtrNext > deqPtr,
          Mux(
            enqPtrNext > numBlocks.U,
            (enqPtrNext % numBlocks.U) > deqPtr,
            false.B
          )
        )
      )
    val empty = Mux(
      ptrMatch,
      !maybeFull,
      Mux(
        deqPtr < enqPtr,
        deqPtrNext > enqPtr,
        Mux(
          deqPtrNext > numBlocks.U,
          (deqPtrNext % numBlocks.U) > enqPtr,
          false.B
        )
      )
    )

    when(io.in.fire =/= io.out.fire) {
      maybeFull := io.in.fire
    }

    when(io.in.fire) {
      for (i <- 0 until blocksPerInput) {
        arr(enqPtr + (blocksPerInput - (i + 1)).U) := io.in.bits(
          (i + 1) * gcd - 1,
          i * gcd
        )
      }
      count(enqPtr, blocksPerInput, numBlocks)
    }

    when(io.out.fire) {
      io.out.bits := Cat(
        for (i <- 0 until blocksPerOutput)
          yield arr(deqPtr + i.U)
      )
      count(deqPtr, blocksPerOutput, numBlocks)
    }.otherwise {
      io.out.bits := DontCare
    }

    io.in.ready := !full
    io.out.valid := !empty
  }

  def count(reg: UInt, step: Int, max: Int): Unit = {
    reg := (reg + step.U) % max.U
  }
}

class WidthConverterTester(inWidth: Int, outWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(UInt(inWidth.W)))
    val out = Decoupled(UInt(inWidth.W))
  })

  val conv0 = Module(new WidthConverter(inWidth, outWidth))
  val conv1 = Module(new WidthConverter(outWidth, inWidth))

  conv0.io.in <> io.in
  conv1.io.in <> conv0.io.out
  io.out <> conv1.io.out
}

class ScratchSpec extends FunUnitSpec {
  describe("Scratch") {}
}

class WidthConverterSpec extends FunUnitSpec {
  describe("WidthConverter") {
    it("should work") {
      decoupledTest(new WidthConverter(64, 72)) { m =>
        m.io.in.setSourceClock(m.clock)
        m.io.out.setSinkClock(m.clock)

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

    def doTest(inWidth: Int, outWidth: Int) {
      it(
        s"should reproduce data correctly with inWidth=$inWidth and outWidth=$outWidth"
      ) {
        decoupledTest(new WidthConverterTester(inWidth, outWidth)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          val n = util.leastCommonMultiple(inWidth, outWidth) * 10
          // make byte stream of incrementing byte values
          val k = inWidth / 8
          val data =
            for (i <- 0 until n)
              yield (for (j <- 0 until k)
                yield (BigInt(i * k + j) % 256) << (j * 8)).reduce(_ | _)
          // println(data.map(_.toString(16)).mkString("\n"))

          thread("in") {
            for (row <- data) {
              m.io.in.enqueue(row.U)
            }
          }

          thread("out") {
            for (row <- data) {
              m.io.out.expectDequeue(row.U)
            }
          }
        }
      }
    }

    val cases = Array(
      (64, 72),
      (72, 64),
      (8, 64),
      (64, 8),
      (16, 32),
      (32, 16),
    )

    for ((inWidth, outWidth) <- cases) {
      doTest(inWidth, outWidth)
    }
  }
}
