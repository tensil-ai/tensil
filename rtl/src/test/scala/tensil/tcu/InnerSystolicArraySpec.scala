/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import chiseltest._
import tensil.UnitSpec
import tensil.data.Tensor

import scala.reflect.ClassTag

class InnerSystolicArraySpec extends UnitSpec {
  behavior of "InnerSystolicArray"

  it should "have a correct golden model for matrix-vector product" in {
    val mat      = Array(Array(1, 2), Array(3, 4))
    val vec      = Array(5, 6)
    val expected = Array(17, 39)
    val result   = Tensor.goldenMatVecMul(mat, vec)
    result should be(expected)
  }

  it should "have a correct golden model for matrix-matrix product" in {
    val a        = Array(Array(1, 2), Array(3, 4))
    val b        = Array(Array(5, 6), Array(7, 8))
    val expected = Array(Array(19, 22), Array(43, 50))
    val result   = Tensor.goldenMatMatMul(a, b)
    result should be(expected)
  }

  it should "timescopes work" in {
    test(new InnerSystolicArray(SInt(8.W), 4, 4)) { m =>
      for (i <- 0 until 10) {
        fork {
          m.clock.step(11)
          m.io.load.expect(false.B)
        }
        timescope {
          m.io.load.poke(true.B)
          m.clock.step()
        }
      }
      m.clock.step(11)
      m.io.load.expect(false.B)
    }
  }

  it should "perform a matrix-vector product" in {
    case class TestCase(
        dataWidth: Int,
        A: Array[Array[BigInt]],
        x: Array[BigInt]
    ) {
      if (A.length == 0) throw new Tensor.DimensionEmptyException
      if (A.head.length == 0) throw new Tensor.DimensionEmptyException
      for (e <- A)
        if (e.length != A.head.length)
          throw new Tensor.DimensionSizeInconsistentException
      val height = A.length
      val width  = A.head.length
    }

    val debug = false
    val testCases = Seq(
      TestCase(8, Array(Array(1, 2), Array(3, 4)), Array(5, 6)),
      TestCase(
        16,
        Array(Array(1, 2, 3), Array(4, 5, 6), Array(7, 8, 9)),
        Array(10, 11, 12)
      ),
      TestCase(16, Array(Array(1, 2), Array(3, 4), Array(5, 6)), Array(7, 8))
    )

    for (tc <- testCases) {
      test(new InnerSystolicArray(SInt(tc.dataWidth.W), tc.height, tc.width)) {
        m =>
          val result   = m.matVecMul(tc.A, tc.x)
          val expected = Tensor.goldenMatVecMul(tc.A, tc.x)
          if (debug) {
            println("result:   [" + result.mkString(" ") + "]")
            println("expected: [" + expected.mkString(" ") + "]")
          }
          m.expectOutput(expected)
          m.clock.step()
          m.expectOutputZero()
      }
    }
  }

  it should "perform a matrix-matrix product" in {
    case class TestCase(
        dataWidth: Int,
        a: Array[Array[BigInt]],
        b: Array[Array[BigInt]]
    ) {
      if (a.length == 0) throw new Tensor.DimensionEmptyException
      if (a.head.length == 0) throw new Tensor.DimensionEmptyException
      for (e <- a)
        if (e.length != a.head.length)
          throw new Tensor.DimensionSizeInconsistentException
      if (a.head.length != b.length)
        throw new Tensor.DimensionSizeMismatchException
      if (b.head.length == 0) throw new Tensor.DimensionEmptyException
      for (e <- b)
        if (e.length != b.head.length)
          throw new Tensor.DimensionSizeInconsistentException
      val height = a.length
      val width  = a.head.length
    }

    val debug = false
    val testCases = Seq(
      TestCase(
        16,
        Array(
          Array(1, 2),
          Array(3, 4),
        ),
        Array(
          Array(5, 6),
          Array(7, 8),
        )
      ),
      TestCase(
        16,
        Array(
          Array(1, 2, 3),
          Array(4, 5, 6),
          Array(7, 8, 9),
        ),
        Array(
          Array(10, 11, 12),
          Array(13, 14, 15),
          Array(16, 17, 18),
        )
      ),
      TestCase(
        16,
        Array(
          Array(1, 2),
          Array(3, 4),
          Array(5, 6),
        ),
        Array(
          Array(7, 8),
          Array(9, 10),
        )
      )
    )

    for (tc <- testCases) {
      test(new InnerSystolicArray(SInt(tc.dataWidth.W), tc.height, tc.width)) {
        m =>
          val expected = Tensor.goldenMatMatMul(tc.a, tc.b)
          val result   = m.matMatMulTest(tc.a, tc.b, expected)
          if (debug) {
            println("result:\n" + matrixToString(result))
            println("expected:\n" + matrixToString(expected))
          }
      }
    }
  }

  // test helpers
  def matrixToString(mat: Array[Array[BigInt]]): String = {
    mat.map(_.mkString(" ")).mkString("\n")
  }

  def transpose(mat: Array[Array[BigInt]]): Array[Array[BigInt]] = {
    val height = mat.length
    val width  = mat.head.length
    val result = Array.fill(width, height)(BigInt(0))
    for (i <- 0 until height) {
      for (j <- 0 until width) {
        result(j)(i) = mat(i)(j)
      }
    }
    result
  }

  implicit class InnerSystolicArrayHelper(
      m: InnerSystolicArray[SInt]
  ) {
    def loadWeights(
        mat: Array[Array[BigInt]]
    ): Unit = {
      // set load
      m.io.load.poke(true.B)
      for (j <- 0 until m.width) {
        for (i <- 0 until m.height) {
          // write in last column first
          m.io.weight(i).poke(mat(i)(m.width - 1 - j).S)
        }
        m.clock.step()
      }
      // set biases to 0
      for (i <- 0 until m.height) {
        m.io.weight(i).poke(0.S)
      }
      m.clock.step()
      // unset load
      m.io.load.poke(false.B)
    }

    def matVecMul(
        mat: Array[Array[BigInt]],
        vec: Array[BigInt]
    ): Array[BigInt] = {
      loadWeights(mat)

      // write in input vector
      for (j <- 0 until m.width) {
        m.io.input(j).poke(vec(j).S)
      }
      m.clock.step()
      for (j <- 0 until m.width) {
        m.io.input(j).poke(0.S)
      }

      // wait for output
      m.clock.step((m.height - 1) + (m.width - 1))
      m.io.output.map(_.peek.litValue).toArray
    }

    def matMatMulTest(
        a: Array[Array[BigInt]],
        b: Array[Array[BigInt]],
        expected: Array[Array[BigInt]]
    ): Array[Array[BigInt]] = {
      loadWeights(a)

      val result = Array.fill(b.head.length)(Array.empty[BigInt])
      // time it takes for the computation to propagate through the array
      val waitTime = (m.height - 1) + (m.width - 1)
      // write in input vector
      for (k <- b.head.indices) {
        timescope {
          for (j <- 0 until m.width) {
            m.io.input(j).poke(b(j)(k).S)
          }
          m.clock.step()
        }
        fork {
          m.clock.step(waitTime)
          expectOutput(expected.map(_(k)))
          result(k) = m.io.output.map(_.peek.litValue).toArray
        }
      }

      m.clock.step(waitTime + 1)
      expectOutputZero()
      transpose(result)
    }

    def expectOutput(
        expected: Array[BigInt]
    ): Unit = {
      for (i <- 0 until m.height) {
        m.io.output(i).expect(expected(i).S)
      }
    }

    def expectOutputZero(): Unit = {
      for (i <- 0 until m.height) {
        m.io.output(i).expect(0.S)
      }
    }
  }
}
