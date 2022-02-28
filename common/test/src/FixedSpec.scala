package tensil

import org.scalatest._
import scala.reflect.ClassTag

import Numeric.Implicits._

class FixedSpec extends FlatSpec {
  behavior of "Fixed"

  def goldenMatMatMul[T : Numeric : ClassTag](
      a: Array[Array[T]],
      b: Array[Array[T]]
  ): Array[Array[T]] = {
    if (a.length == 0) return Array.empty[Array[T]]
    if (a.head.length != b.length)
      throw new Exception("Dimension size mismatch")
    if (a.head.length == 0) return Array.empty[Array[T]]
    if (b.head.length == 0) return Array.empty[Array[T]]
    val result =
      Array.fill(a.length, b.head.length)(implicitly[Numeric[T]].zero)
    for (i <- a.indices) {
      for (j <- a.head.indices) {
        for (k <- b.head.indices) {
          result(i)(k) += a(i)(j) * b(j)(k)
        }
      }
    }
    result
  }

  it should "Fixed16bp8 matrix multiplication within error" in {
    val a =
      Array(Array(1.0, 2.0, 3.0), Array(4.0, 5.0, 6.0), Array(7.0, 8.0, 9.0))
    val b =
      Array(Array(.1, .2, .3), Array(.4, .5, .6), Array(.7, .8, .9))

    val yExpected = goldenMatMatMul(a, b).flatten

    val af = a.map(_.map(Fixed16bp8.fromDouble(_)))
    val bf = b.map(_.map(Fixed16bp8.fromDouble(_)))

    val yf = goldenMatMatMul(af, bf)

    val y = yf.map(_.map(_.toDouble())).flatten

    val e = 0.01f
    def equalE(y: Double, yExpected: Double) = {
      println(s"expected = $yExpected, actual = $y")
      y < (yExpected + e) && y > (yExpected - e)
    }

    for (i <- 0 until yExpected.size)
      assert(equalE(y(i), yExpected(i)))
  }

  it should "Fixed18bp10 matrix multiplication within error" in {
    val a =
      Array(Array(1.0, 2.0, 3.0), Array(4.0, 5.0, 6.0), Array(7.0, 8.0, 9.0))
    val b =
      Array(Array(.1, .2, .3), Array(.4, .5, .6), Array(.7, .8, .9))

    val yExpected = goldenMatMatMul(a, b).flatten

    val af = a.map(_.map(Fixed18bp10.fromDouble(_)))
    val bf = b.map(_.map(Fixed18bp10.fromDouble(_)))

    val yf = goldenMatMatMul(af, bf)

    val y = yf.map(_.map(_.toDouble())).flatten

    val e = 0.003f
    def equalE(y: Double, yExpected: Double) = {
      println(s"expected = $yExpected, actual = $y")
      y < (yExpected + e) && y > (yExpected - e)
    }

    for (i <- 0 until yExpected.size)
      assert(equalE(y(i), yExpected(i)))
  }

  it should "Fixed32bp16 matrix multiplication within error" in {
    val a =
      Array(Array(1.0, 2.0, 3.0), Array(4.0, 5.0, 6.0), Array(7.0, 8.0, 9.0))
    val b =
      Array(Array(.1, .2, .3), Array(.4, .5, .6), Array(.7, .8, .9))

    val yExpected = goldenMatMatMul(a, b).flatten

    val af = a.map(_.map(Fixed32bp16.fromDouble(_)))
    val bf = b.map(_.map(Fixed32bp16.fromDouble(_)))

    val yf = goldenMatMatMul(af, bf)

    val y = yf.map(_.map(_.toDouble())).flatten

    val e = 0.0001f
    def equalE(y: Double, yExpected: Double) = {
      println(s"expected = $yExpected, actual = $y")
      y < (yExpected + e) && y > (yExpected - e)
    }

    for (i <- 0 until yExpected.size)
      assert(equalE(y(i), yExpected(i)))
  }

  it should "Fixed16bp8 to bytes and from bytes" in {
    val e = 0.001f
    def equalE(y: Double, yExpected: Double) = {
      println(s"expected = $yExpected, actual = $y")
      y < (yExpected + e) && y > (yExpected - e)
    }

    val fs = List(
      Fixed16bp8.MaxValue.toDouble(),
      Fixed16bp8.MinValue.toDouble(),
      0.9110825,
      -0.9110825
    )

    for (f <- fs) {
      val bytes = Fixed16bp8.toBytes(Fixed16bp8.fromDouble(f))
      assert(equalE(Fixed16bp8.fromBytes(bytes).toDouble(), f))
    }
  }

  it should "Fixed18bp10 to bytes and from bytes" in {
    val e = 0.0001f
    def equalE(y: Double, yExpected: Double) = {
      println(s"expected = $yExpected, actual = $y")
      y < (yExpected + e) && y > (yExpected - e)
    }

    val fs = List(
      Fixed18bp10.MaxValue.toDouble(),
      Fixed18bp10.MinValue.toDouble(),
      0.9110825,
      -0.9110825
    )

    for (f <- fs) {
      val bytes = Fixed18bp10.toBytes(Fixed18bp10.fromDouble(f))
      assert(equalE(Fixed18bp10.fromBytes(bytes).toDouble(), f))
    }
  }

  it should "Fixed32bp16 to bytes and from bytes" in {
    val e = 0.00001f
    def equalE(y: Double, yExpected: Double) = {
      println(s"expected = $yExpected, actual = $y")
      y < (yExpected + e) && y > (yExpected - e)
    }

    val fs = List(
      Fixed32bp16.MaxValue.toDouble(),
      Fixed32bp16.MinValue.toDouble(),
      0.9110825,
      -0.9110825
    )

    for (f <- fs) {
      val bytes = Fixed32bp16.toBytes(Fixed32bp16.fromDouble(f))
      assert(equalE(Fixed32bp16.fromBytes(bytes).toDouble(), f))
    }
  }
}
