package tf2rtl.data

import org.scalatest.FlatSpec

class ConvSpec extends FlatSpec {
  behavior of "Conv"

  it should "conv2d" in {
    {
      val input  = Array.tabulate(4, 4)((i, j) => BigInt(i * 4 + j))
      val filter = Array.fill(2, 2)(BigInt(1))
      val output = Array.tabulate(3, 3)((i, j) => BigInt(10 + i * 16 + j * 4))

      assert(Conv.convSingleChannel(input, filter) === output)
    }
    {
      val input  = Array.tabulate(4, 4)((i, j) => BigInt(i * 4 + j))
      val filter = Array.tabulate(2, 2)((i, j) => BigInt(i * 2 + j))
      val output = Array.tabulate(3, 3)((i, j) => BigInt(24 + i * 24 + j * 6))

      assert(Conv.convSingleChannel(input, filter) === output)
    }
  }

  it should "conv3d" in {
    {
      val input =
        Array.tabulate(2, 4, 4)((c, i, j) => BigInt(c * 16 + i * 4 + j))
      val weights = Array.tabulate(2, 2, 2, 2)((o, i, fi, fj) =>
        BigInt(o * 8 + i * 4 + fi * 2 + fj)
      )
      val output = Array.tabulate(2, 3, 3)((c, i, j) =>
        BigInt(440 + c * 672 + i * (112 + c * 256) + j * (28 + c * 64))
      )

      assert(Conv.conv2d(input, weights) === output)
    }
  }

  it should "padSame" in {
    val input              = Array.fill(5, 5)(BigInt(1))
    val filter: (Int, Int) = (3, 3)
    val output = Array.tabulate(7, 7)((i, j) => {
      if (i == 0 || j == 0 || i == 6 || j == 6) {
        BigInt(0)
      } else {
        BigInt(1)
      }
    })

    assert(Conv.padSame(input, filter, BigInt(0)) === output)
  }

  it should "padSame3d" in {
    val input              = Array.fill(2, 5, 5)(BigInt(1))
    val filter: (Int, Int) = (3, 3)
    val output = Array.tabulate(2, 7, 7)((_, i, j) => {
      if (i == 0 || j == 0 || i == 6 || j == 6) {
        BigInt(0)
      } else {
        BigInt(1)
      }
    })

    assert(Conv.padSame(input, filter, BigInt(0)) === output)
  }
}
