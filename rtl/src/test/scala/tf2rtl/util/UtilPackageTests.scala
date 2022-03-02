/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.util.{Decoupled, Queue}
import chiseltest._
import tensil.{FixedDriver, FixedPeekPokeTester, Scratch, Treadle, UnitSpec}

class UtilPackageTests extends UnitSpec {

  "times (fixed point)" should "work" in {
    class TimesFixedPointModule extends Module {
      val gen = FixedPoint(16.W, 8.BP)
      val io = IO(new Bundle {
        val x      = Input(gen)
        val y      = Input(gen)
        val result = Output(gen)
        val old    = Output(gen)
      })

      io.result := times(gen, io.x, io.y)
      io.old := io.x * io.y
    }
    val testCases = Array(
      (-0.1, 0.265625, -0.02734375),
      (-0.125, 0.25, -0.03125),
      (0.1, 0.265625, 0.02734375),
      (100.0, 2.65625, 127.99609), // overflow saturation
      (100.0, -2.65625, -128.0),   // underflow saturation
    )

    test(new TimesFixedPointModule) { m =>
      for (tc <- testCases) {
        m.io.x.poke(tc._1.F(16.W, 8.BP))
        m.io.y.poke(tc._2.F(16.W, 8.BP))
        m.io.result.expect(tc._3.F(16.W, 8.BP))
        println(m.io.old.peek())
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
        val old    = Output(gen)
      })

      io.result := plus(gen, io.x, io.y)
      io.old := io.x + io.y
    }
    val testCases = Array(
      (-0.1, 0.265625, 0.1640625),
      (-0.125, 0.25, 0.125),
      (0.1, 0.265625, 0.3671875),
      (100.0, 100.0, 127.99609), // overflow saturation
      (-100.0, -100.0, -128.0),  // underflow saturation
    )

    test(new PlusFixedPointModule) { m =>
      for (tc <- testCases) {
        m.io.x.poke(tc._1.F(16.W, 8.BP))
        m.io.y.poke(tc._2.F(16.W, 8.BP))
        m.io.result.expect(tc._3.F(16.W, 8.BP))
        println(m.io.old.peek())
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
        val old    = Output(gen)
      })

      io.result := minus(gen, io.x, io.y)
      io.old := io.x - io.y
    }
    val testCases = Array(
      (-0.1, 0.265625, -0.3671875),
      (-0.125, 0.25, -0.375),
      (0.1, 0.265625, -0.1640625),
      (100.0, -100.0, 127.99609), // overflow saturation
      (-100.0, 100.0, -128.0),    // underflow saturation
    )

    test(new MinusFixedPointModule) { m =>
      for (tc <- testCases) {
        m.io.x.poke(tc._1.F(16.W, 8.BP))
        m.io.y.poke(tc._2.F(16.W, 8.BP))
        m.io.result.expect(tc._3.F(16.W, 8.BP))
        println(m.io.old.peek())
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
    FixedDriver(
      () => new SignedModTest(4, 16),
      generateVCD = true,
      backend = Treadle
    ) { dut =>
      new FixedPeekPokeTester(dut) {
        val tests = Array((5, 6, 15))

        for ((a, b, c) <- tests) {
          poke(dut.io.a, a)
          poke(dut.io.b, b)
          expect(dut.io.c, c)
        }
      }
    } should be(true)

    FixedDriver(
      () => new SignedModTest(2, 3),
      generateVCD = true,
      backend = Treadle
    ) { dut =>
      new FixedPeekPokeTester(dut) {
        val tests = Array((0, 1, 2), (1, 2, 2), (0, 2, 1))

        for ((a, b, c) <- tests) {
          poke(dut.io.a, a)
          poke(dut.io.b, b)
          expect(dut.io.c, c)
        }
      }
    } should be(true)
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
