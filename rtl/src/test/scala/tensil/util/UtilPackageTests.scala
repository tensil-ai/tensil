/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util

import scala.util.Random
import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.util.{Decoupled, Queue}
import chiseltest._
import tensil.UnitSpec
import tensil.Fixed16bp8

import Numeric.Implicits._

class UtilPackageTests extends UnitSpec {
  private val random          = new Random()
  private val testCasesNumber = 1000000

  private def next: Float = {
    random.nextFloat() * (Fixed16bp8.MaxValue.toFloat() - Fixed16bp8.MinValue
      .toFloat()) + Fixed16bp8.MinValue.toFloat()
  }

  private def mkPairs(n: Int) = {
    for (_ <- 0 until n) yield (next, next)
  }

  private def mkTriples(n: Int) = {
    for (_ <- 0 until n) yield (next, next, next)
  }

  "mac (fixed point)" should "work" in {
    class MACFixedPointModule extends Module {
      val gen = FixedPoint(16.W, 8.BP)
      val io = IO(new Bundle {
        val x      = Input(gen)
        val y      = Input(gen)
        val z      = Input(gen)
        val result = Output(gen)
      })

      io.result := mac(gen, io.x, io.y, io.z)
    }
    val testCases = mkTriples(testCasesNumber)

    test(new MACFixedPointModule) { m =>
      for (tc <- testCases) {
        m.io.x.poke(tc._1.F(16.W, 8.BP))
        m.io.y.poke(tc._2.F(16.W, 8.BP))
        m.io.z.poke(tc._3.F(16.W, 8.BP))
        m.io.result.expect(
          (Fixed16bp8.numericWithMAC
            .mac(
              Fixed16bp8.fromFloat(tc._1),
              Fixed16bp8.fromFloat(tc._2),
              Fixed16bp8.fromFloat(tc._3)
            ))
            .toFloat
            .F(16.W, 8.BP)
        )
      }
    }
  }
  "times (fixed point)" should "work" in {
    class TimesFixedPointModule extends Module {
      val gen = FixedPoint(16.W, 8.BP)
      val io = IO(new Bundle {
        val x      = Input(gen)
        val y      = Input(gen)
        val result = Output(gen)
      })

      io.result := times(gen, io.x, io.y)
    }
    val testCases = mkPairs(testCasesNumber)

    test(new TimesFixedPointModule) { m =>
      for (tc <- testCases) {
        m.io.x.poke(tc._1.F(16.W, 8.BP))
        m.io.y.poke(tc._2.F(16.W, 8.BP))
        m.io.result.expect(
          (Fixed16bp8.fromFloat(tc._1) * Fixed16bp8.fromFloat(tc._2)).toFloat
            .F(16.W, 8.BP)
        )
      }
    }
  }

  "plus (fixed point)" should "work" in {
    class PlusFixedPointModule extends Module {
      val gen = FixedPoint(16.W, 8.BP)
      val io = IO(new Bundle {
        val x      = Input(gen)
        val y      = Input(gen)
        val result = Output(gen)
      })

      io.result := plus(gen, io.x, io.y)
    }
    val testCases = mkPairs(testCasesNumber)

    test(new PlusFixedPointModule) { m =>
      for (tc <- testCases) {
        m.io.x.poke(tc._1.F(16.W, 8.BP))
        m.io.y.poke(tc._2.F(16.W, 8.BP))
        m.io.result.expect(
          (Fixed16bp8.fromFloat(tc._1) + Fixed16bp8.fromFloat(tc._2)).toFloat
            .F(16.W, 8.BP)
        )
      }
    }
  }

  "minus (fixed point)" should "work" in {
    class MinusFixedPointModule extends Module {
      val gen = FixedPoint(16.W, 8.BP)
      val io = IO(new Bundle {
        val x      = Input(gen)
        val y      = Input(gen)
        val result = Output(gen)
      })

      io.result := minus(gen, io.x, io.y)
    }
    val testCases = mkPairs(testCasesNumber)

    test(new MinusFixedPointModule) { m =>
      for (tc <- testCases) {
        m.io.x.poke(tc._1.F(16.W, 8.BP))
        m.io.y.poke(tc._2.F(16.W, 8.BP))
        m.io.result.expect(
          (Fixed16bp8.fromFloat(tc._1) - Fixed16bp8.fromFloat(tc._2)).toFloat
            .F(16.W, 8.BP)
        )
      }
    }
  }

  "greatestCommonDivisor" should "work" in {
    val testCases = Array(
      (40, 32, 8),
      (2, 1, 1),
      (0, 1, 1),
      (41, 32, 1),
    )

    for (tc <- testCases) {
      val gcd = greatestCommonDivisor(tc._1, tc._2)
      assert(gcd == tc._3, s"gcd(${tc._1}, ${tc._2}) == ${tc._3} but got $gcd")
    }
  }

  "leastCommonMultiple" should "work" in {
    val testCases = Array(
      (8, 9, 72),
      (1, 4, 4),
      (90, 342, 1710),
      (7, 13, 91),
    )

    for (tc <- testCases) {
      val lcm = leastCommonMultiple(tc._1, tc._2)
      assert(lcm == tc._3, s"lcm(${tc._1}, ${tc._2}) == ${tc._3} but got $lcm")
    }
  }

  "splitWords" should "turn a bigger word into several smaller ones" in {
    val x        = BigInt("0102030405060708", 16)
    val expected = Array(BigInt("05060708", 16), BigInt("01020304", 16))
    splitWord(x, 32) should equal(expected)
  }

  "combineWords" should "turn several smaller words into one big ones" in {
    val words    = Array(BigInt("05060708", 16), BigInt("01020304", 16))
    val expected = BigInt("0102030405060708", 16)
    combineWords(words, 32) should equal(expected)
  }

  "streamTransmission" should "convert a stream of 32-bit words into a stream of 160-bit words" in {
    val input = Array(
      BigInt("11121314", 16),
      BigInt("0d0e0f10", 16),
      BigInt("090a0b0c", 16),
      BigInt("05060708", 16),
      BigInt("01020304", 16),
    )
    val expected = Array(BigInt("0102030405060708090a0b0c0d0e0f1011121314", 16))
    streamTransmission(input, 32, 160) should equal(expected)
  }

  "streamTransmission" should "work when the inputs need to be flushed" in {
    val input = Array(
      BigInt("11121314", 16),
      BigInt("0d0e0f10", 16),
      BigInt("090a0b0c", 16),
      BigInt("05060708", 16),
    )
    val expected = Array(BigInt("05060708090a0b0c0d0e0f1011121314", 16))
    streamTransmission(input, 32, 160) should equal(expected)
  }

  "streamTransmission" should "convert a stream of 160-bit words into a stream of 32-bit words" in {
    val input = Array(BigInt("0102030405060708090a0b0c0d0e0f1011121314", 16))
    val expected = Array(
      BigInt("11121314", 16),
      BigInt("0d0e0f10", 16),
      BigInt("090a0b0c", 16),
      BigInt("05060708", 16),
      BigInt("01020304", 16),
    )
    streamTransmission(input, 160, 32) should equal(expected)
  }

  "streamTransmission" should "work when some values returned from splitWords need to be zero padded" in {
    val input = Array(
      BigInt("0", 16),
      BigInt("10000", 16),
      BigInt("50003", 16),
      BigInt("c0005", 16),
      BigInt("140010", 16),
    )
    val expected = Array(
      BigInt("0", 16),
      BigInt("0", 16),
      BigInt("0", 16),
      BigInt("1", 16),
      BigInt("3", 16),
      BigInt("5", 16),
      BigInt("5", 16),
      BigInt("c", 16),
      BigInt("10", 16),
      BigInt("14", 16),
    )
    streamTransmission(input, 32, 16) should equal(expected)
  }

  "signedMod" should "be correct" in {
    test(new SignedModTest(4, 16)) { dut =>
      val tests = Array((5, 6, 15))

      for ((a, b, c) <- tests) {
        dut.io.a.poke(a)
        dut.io.b.poke(b)
        dut.io.c.expect(c)
      }
    }

    test(new SignedModTest(2, 3)) { dut =>
      val tests = Array((0, 1, 2), (1, 2, 2), (0, 2, 1))

      for ((a, b, c) <- tests) {
        dut.io.a.poke(a)
        dut.io.b.poke(b)
        dut.io.c.expect(c)
      }
    }
  }

  "extractBitField" should "include low endpoint, exclude high endpoint" in {
    val input: BigInt    = 0xff0102ff
    val expected: BigInt = 0x0102
    extractBitField(input, 8, 24) should equal(expected)
  }

  "bitMask" should "work with big shifts" in {
    val to       = 94
    val from     = 15
    val expected = ((BigInt(1) << to) - 1) - ((BigInt(1) << from) - 1)
    bitMask(from, to) should equal(expected)

  }
}

class SignedModTest(busWidth: Int, mod: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(busWidth.W))
    val b = Input(UInt(busWidth.W))
    val c = Output(UInt(busWidth.W))
  })

  val m = signedMod(io.a.asSInt - io.b.asSInt, mod).asUInt

  io.c := m
}
