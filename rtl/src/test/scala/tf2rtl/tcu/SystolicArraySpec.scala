package tensil.tcu

import chisel3._
import chiseltest._
import chisel3.experimental.BundleLiterals._
import scala.collection.mutable
import tensil.UnitSpec
import tensil.data.Tensor
import tensil.decoupled.{decoupledToDriver, decoupledVecToDriver}
import chiseltest.internal.TesterThreadList

class SystolicArraySpec extends UnitSpec {
  behavior of "SystolicArray"

  it should "run one matrix-vector product every cycle" in {
    val gen    = SInt(8.W)
    val height = 4

    test(new SystolicArray(SInt(8.W), height, height)) { m =>
      m.io.control.setSourceClock(m.clock)
      m.io.weight.setSourceClock(m.clock)
      m.io.input.setSourceClock(m.clock)
      m.io.output.setSinkClock(m.clock)

      val threads     = new mutable.ArrayBuffer[TesterThreadList]
      val numProducts = 2 * height
      val identity: Array[Array[BigInt]] = Array(
        Array(0, 0, 0, 1),
        Array(0, 0, 1, 0),
        Array(0, 1, 0, 0),
        Array(1, 0, 0, 0),
        Array(0, 0, 0, 0),
      )
      val vec: Array[BigInt] = Array(1, 2, 3, 4)

      threads += fork {
        for (i <- 0 until (height + 1)) {
          m.io.control.enqueue(
            chiselTypeOf(m.io.control.bits)
              .Lit(_.load -> true.B, _.zeroes -> false.B)
          )
        }
        for (i <- 0 until numProducts) {
          m.io.control.enqueue(
            chiselTypeOf(m.io.control.bits)
              .Lit(_.load -> false.B, _.zeroes -> false.B)
          )
        }
      }

      threads += fork {
        for (i <- 0 until (height + 1)) {
          m.io.weight.enqueue(
            identity(i).map(_.S)
          )
        }
      }

      threads += fork {
        for (i <- 0 until numProducts) {
          m.io.input.enqueue(vec.map(_.S))
        }
      }

      threads += fork {
        var first = true
        for (i <- 0 until numProducts) {
          if (!first) {
            // pipeline should remain valid after it starts to produce outputs
            m.io.output.valid.expect(true.B)
          }
          first = false
          m.io.output.expectDequeue(vec.map(_.S))
        }
      }

      threads.map(_.join)
    }
  }

  it should "perform a matrix-vector product with zeroes" in {
    val debug = false
    val A: Array[Array[BigInt]] =
      Array(Array(1, 2, 3), Array(4, 5, 6), Array(7, 8, 9))
    val z: Array[BigInt] = Array(0, 0, 0)
    decoupledTest(
      new SystolicArray(SInt(8.W), 3, 3)
    ) { m =>
      m.io.control.setSourceClock(m.clock)
      m.io.weight.setSourceClock(m.clock)
      m.io.input.setSourceClock(m.clock)
      m.io.output.setSinkClock(m.clock)

      thread("input") {}
      m.loadWeights(A)
      m.matVecMulZeroes(A)
      val expected = Tensor.goldenMatVecMul(A, z)
      m.expectVector(expected)
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
      decoupledTest(
        new SystolicArray(SInt(tc.dataWidth.W), tc.height, tc.width)
      ) { m =>
        m.io.control.setSourceClock(m.clock)
        m.io.weight.setSourceClock(m.clock)
        m.io.input.setSourceClock(m.clock)
        m.io.output.setSinkClock(m.clock)
        m.matVecMul(tc.A, tc.x)
        val expected = Tensor.goldenMatVecMul(tc.A, tc.x)
        m.expectVector(expected)
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
      decoupledTest(
        new SystolicArray(SInt(tc.dataWidth.W), tc.height, tc.width)
      ) { m =>
        m.io.control.setSourceClock(m.clock)
        m.io.weight.setSourceClock(m.clock)
        m.io.input.setSourceClock(m.clock)
        m.io.output.setSinkClock(m.clock)
        val expected = Tensor.goldenMatMatMul(tc.a, tc.b)
        m.matMatMulTest(tc.a, tc.b, expected)
      }
    }
  }

  it should "work when the input is delayed behind the control signal" in {
    decoupledTest(
      new SystolicArray(SInt(16.W), 2, 2)
    ) { m =>
      m.io.control.setSourceClock(m.clock)
      m.io.input.setSourceClock(m.clock)
      m.io.output.setSinkClock(m.clock)
      m.io.weight.setSourceClock(m.clock)

      val weights: Array[Array[BigInt]] = Array(
        Array(1, 2),
        Array(3, 4),
      )
      val input: Array[Array[BigInt]] = Array(
        Array(5, 6),
        Array(7, 8),
      )
      val expected = Tensor.goldenMatMatMul(weights, input)

      m.loadWeights(weights)

      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(_.load -> false.B, _.zeroes -> false.B)
        )
        m.clock.step(10)
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(_.load -> false.B, _.zeroes -> false.B)
        )
      }

      thread("input") {
        m.io.input.enqueue(input.map(_(0).S))
        m.io.input.enqueue(input.map(_(1).S))
      }

      thread("output") {
        m.io.output.expectDequeue(expected.map(_(0).S))
        m.io.output.expectDequeue(expected.map(_(1).S))
      }
    }
  }

  it should "perform repeated mat-vec muls" in {
    decoupledTest(
      new SystolicArray(SInt(16.W), 2, 2)
    ) { m =>
      m.io.control.setSourceClock(m.clock)
      m.io.input.setSourceClock(m.clock)
      m.io.output.setSinkClock(m.clock)
      m.io.weight.setSourceClock(m.clock)

      val weights: Array[Array[BigInt]] = Array(
        Array(1, 2),
        Array(3, 4),
      )
      val input: Array[Array[BigInt]] = Array(
        Array(5, 6),
        Array(7, 8),
      )
      val expected = Tensor.goldenMatMatMul(weights, input)

      for (i <- 0 until 100) {
        m.loadWeights(weights)
        m.mul(input.map(_(0)))

        thread("output") {
          m.io.output.expectDequeue(expected.map(_(0).S))
        }
      }

      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(_.load -> false.B, _.zeroes -> false.B)
        )
        m.clock.step(10)
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(_.load -> false.B, _.zeroes -> false.B)
        )
      }

      thread("input") {
        m.io.input.enqueue(input.map(_(0).S))
        m.io.input.enqueue(input.map(_(1).S))
      }

      thread("output") {
        m.io.output.expectDequeue(expected.map(_(0).S))
        m.io.output.expectDequeue(expected.map(_(1).S))
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

  implicit class SystolicArrayHelper(m: SystolicArray[SInt]) {
    def expectMatrix(mat: Array[Array[BigInt]]): Unit =
      thread("output") {
        for (i <- mat.head.indices)
          m.io.output.expectDequeue(mat.map(_(i).S))
      }

    def expectVector(vec: Array[BigInt]): Unit =
      thread("output") {
        m.io.output.expectDequeue(vec.map(_.S))
      }

    def loadWeights(
        mat: Array[Array[BigInt]]
    ): Unit = {
      thread("control") {
        for (j <- 0 until m.width) {
          m.io.control.enqueue(
            chiselTypeOf(m.io.control.bits)
              .Lit(_.load -> true.B, _.zeroes -> false.B)
          )
        }
        // set biases to 0
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(_.load -> true.B, _.zeroes -> true.B)
        )
      }

      thread("weight") {
        for (j <- 0 until m.width) {
          m.io.weight.enqueue(
            for (i <- 0 until m.height) yield mat(i)(m.width - 1 - j).S
          )
        }
      }
    }

    def matVecMul(
        mat: Array[Array[BigInt]],
        vec: Array[BigInt]
    ): Unit = {
      loadWeights(mat)

      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(_.load -> false.B, _.zeroes -> false.B)
        )
      }
      thread("input") {
        // write in input vector
        m.io.input.enqueue(vec.map(_.S))
      }
    }

    def matVecMulZeroes(
        mat: Array[Array[BigInt]],
    ): Unit = {
      // write in input vector
      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(_.load -> false.B, _.zeroes -> true.B)
        )
      }
    }

    def mul(vec: Array[BigInt]): Unit = {
      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(_.load -> false.B, _.zeroes -> false.B)
        )
      }
      thread("input") {
        m.io.input.enqueue(vec.map(_.S))
      }
    }

    def matMatMulTest(
        a: Array[Array[BigInt]],
        b: Array[Array[BigInt]],
        expected: Array[Array[BigInt]]
    ): Unit = {
      loadWeights(a)

      // write in input vector
      for (k <- b.head.indices) {
        thread("control") {
          m.io.control.enqueue(
            chiselTypeOf(m.io.control.bits)
              .Lit(_.load -> false.B, _.zeroes -> false.B)
          )
        }
        thread("input") {
          m.io.input.enqueue(for (j <- 0 until m.width) yield b(j)(k).S)
        }
        thread("output") {
          m.io.output.expectDequeue(expected.map(_(k).S))
        }
      }
    }
  }
}
