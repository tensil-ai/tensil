package tf2rtl.tcu

import tf2rtl.UnitSpec
import tf2rtl.data.{Index, MultiRange, Shape, Slice, Tensor}

class TensorSpec extends UnitSpec {
  behavior of "Tensor"

  it should "max" in {
    val t = new Tensor(Array(1, 2, 3, 4), Shape(2, 2))
    assert(t.max == 4)
  }

  it should "goldenMaxPool" in {
    {
      val image = new Tensor((1 until 17).toArray, Shape(4, 4, 1))
      var pool  = Tensor.goldenMaxPool(image, 2, 2, 2)

      assert(pool == new Tensor(Array(6, 8, 14, 16), Shape(2, 2, 1)))
    }

    {
      val image = new Tensor((1 until 26).toArray, Shape(5, 5, 1))
      var pool  = Tensor.goldenMaxPool(image, 3, 3, 2)

      assert(pool == new Tensor(Array(13, 15, 23, 25), Shape(2, 2, 1)))
    }
  }

  it should "index properly" in {
    val data = Array(
      Array(1, 2, 3),
      Array(4, 5, 6),
      Array(7, 8, 9),
    )
    val shape = Shape(3, 3)
    val expectedData = Array(
      Array(1, 2, 3),
    )
    val expectedShape = Shape(1, 3)

    val t   = new Tensor(data.flatten, shape)
    val et  = new Tensor(expectedData.flatten, expectedShape)
    val idx = Index(Array(Left(0), Right(Slice.all)))
    assert(t(idx) == et)
    assert(t(idx).squash == new Tensor(Array(1, 2, 3), Shape(3)))
  }

  it should "slice multiple axes at once" in {
    val data = Array(
      Array(1, 2, 3),
      Array(4, 5, 6),
      Array(7, 8, 9),
    )
    val shape = Shape(3, 3)
    val expectedData = Array(
      Array(1, 2),
      Array(4, 5),
    )
    val expectedShape = Shape(2, 2)

    val t   = new Tensor(data.flatten, shape)
    val et  = new Tensor(expectedData.flatten, expectedShape)
    val idx = Index(Array(Right(Slice(2)), Right(Slice(2))))

    assert(t(idx) == et)
  }

  it should "work in 3-dimensions" in {
    val data = Array(
      Array(
        Array(1, 2, 3),
        Array(3, 4, 5),
        Array(6, 7, 8),
      ),
      Array(
        Array(5, 6, 7),
        Array(7, 8, 9),
        Array(10, 11, 12),
      ),
      Array(
        Array(13, 14, 15),
        Array(16, 17, 18),
        Array(19, 20, 21),
      )
    )
    val shape = Shape(3, 3, 3)
    val expectedData = Array(
      Array(
        Array(1, 2),
        Array(3, 4),
      ),
      Array(
        Array(5, 6),
        Array(7, 8),
      )
    )
    val expectedShape = Shape(2, 2, 2)

    val t   = new Tensor(data.map(_.flatten).flatten, shape)
    val et  = new Tensor(expectedData.map(_.flatten).flatten, expectedShape)
    val idx = Index(Array(Right(Slice(2)), Right(Slice(2)), Right(Slice(2))))
    assert(t(idx) == et)
  }

  it should "pad correctly" in {
    {
      val image  = Array.fill(2, 3, 3)(BigInt(1))
      val imageT = new Tensor(Tensor.flatten(image), Shape(2, 3, 3))
      val out = Array(
        Array(
          Array(1, 1, 1, 0),
          Array(1, 1, 1, 0),
          Array(1, 1, 1, 0),
          Array(0, 0, 0, 0),
        ),
        Array(
          Array(1, 1, 1, 0),
          Array(1, 1, 1, 0),
          Array(1, 1, 1, 0),
          Array(0, 0, 0, 0),
        ),
      )
      val outT   = new Tensor(Tensor.flatten(out), Shape(2, 4, 4))
      val result = imageT.pad(BigInt(0), Array((0, 0), (0, 1), (0, 1)))
      assert(result == outT)
    }
    {
      val image: Array[Array[Array[BigInt]]] = Array(
        Array(
          Array(0, 1, 2, 3),
          Array(4, 5, 6, 7),
          Array(8, 9, 10, 11),
        ),
        Array(
          Array(12, 13, 14, 15),
          Array(16, 17, 18, 19),
          Array(20, 21, 22, 23),
        ),
        Array(
          Array(24, 25, 26, 27),
          Array(28, 29, 30, 31),
          Array(32, 33, 34, 35),
        )
      )
      val imageT = new Tensor(image.map(_.flatten).flatten, Shape(3, 3, 4))
      val out: Array[Array[Array[BigInt]]] = Array(
        Array(
          Array(0, 1, 2, 3),
          Array(4, 5, 6, 7),
          Array(8, 9, 10, 11),
          Array(0, 0, 0, 0),
        ),
        Array(
          Array(12, 13, 14, 15),
          Array(16, 17, 18, 19),
          Array(20, 21, 22, 23),
          Array(0, 0, 0, 0),
        ),
        Array(
          Array(24, 25, 26, 27),
          Array(28, 29, 30, 31),
          Array(32, 33, 34, 35),
          Array(0, 0, 0, 0),
        ),
        Array(
          Array(0, 0, 0, 0),
          Array(0, 0, 0, 0),
          Array(0, 0, 0, 0),
          Array(0, 0, 0, 0),
        )
      )
      val outT   = new Tensor(Tensor.flatten(out), Shape(4, 4, 4))
      val result = imageT.pad(BigInt(0), Array((0, 1), (0, 1), (0, 0)))
      assert(result == outT)
    }
  }

  it should "transpose correctly" in {
    {
      val image = Array(
        Array(
          Array(1, 2, 3),
          Array(1, 2, 3),
          Array(1, 2, 3),
          Array(1, 2, 3),
        ),
        Array(
          Array(1, 2, 3),
          Array(1, 2, 3),
          Array(1, 2, 3),
          Array(1, 2, 3),
        ),
        Array(
          Array(1, 2, 3),
          Array(1, 2, 3),
          Array(1, 2, 3),
          Array(1, 2, 3),
        ),
        Array(
          Array(1, 2, 3),
          Array(1, 2, 3),
          Array(1, 2, 3),
          Array(1, 2, 3),
        ),
      )
      val imageT = new Tensor(Tensor.flatten(image), Shape(4, 4, 3))
      val out = Array(
        Array(
          Array(1, 1, 1, 1),
          Array(1, 1, 1, 1),
          Array(1, 1, 1, 1),
          Array(1, 1, 1, 1),
        ),
        Array(
          Array(2, 2, 2, 2),
          Array(2, 2, 2, 2),
          Array(2, 2, 2, 2),
          Array(2, 2, 2, 2),
        ),
        Array(
          Array(3, 3, 3, 3),
          Array(3, 3, 3, 3),
          Array(3, 3, 3, 3),
          Array(3, 3, 3, 3),
        ),
      )
      val outT   = new Tensor(Tensor.flatten(out), Shape(3, 4, 4))
      val result = imageT.transpose(Array(2, 0, 1))
      assert(result == outT)
    }
    {
      val image: Array[Array[Array[BigInt]]] = Array(
        Array(
          Array(0, 1, 2, 3),
          Array(4, 5, 6, 7),
          Array(8, 9, 10, 11),
        ),
        Array(
          Array(12, 13, 14, 15),
          Array(16, 17, 18, 19),
          Array(20, 21, 22, 23),
        ),
        Array(
          Array(24, 25, 26, 27),
          Array(28, 29, 30, 31),
          Array(32, 33, 34, 35),
        )
      )
      val imageT = new Tensor(Tensor.flatten(image), Shape(3, 3, 4))
      val expected = Array(
        Array(
          Array(0, 4, 8),
          Array(12, 16, 20),
          Array(24, 28, 32),
        ),
        Array(
          Array(1, 5, 9),
          Array(13, 17, 21),
          Array(25, 29, 33),
        ),
        Array(
          Array(2, 6, 10),
          Array(14, 18, 22),
          Array(26, 30, 34),
        ),
        Array(
          Array(3, 7, 11),
          Array(15, 19, 23),
          Array(27, 31, 35),
        ),
      )
      val expectedT = new Tensor(Tensor.flatten(expected), Shape(4, 3, 3))
      val result    = imageT.transpose(Array(2, 0, 1))
      assert(result == expectedT)
    }
    {
      val image = Array(
        Array(
          Array(0, 1, 2, 3),
          Array(4, 5, 6, 7),
          Array(8, 9, 10, 11),
          Array(0, 0, 0, 0),
        ),
        Array(
          Array(12, 13, 14, 15),
          Array(16, 17, 18, 19),
          Array(20, 21, 22, 23),
          Array(0, 0, 0, 0),
        ),
        Array(
          Array(24, 25, 26, 27),
          Array(28, 29, 30, 31),
          Array(32, 33, 34, 35),
          Array(0, 0, 0, 0),
        ),
        Array(
          Array(0, 0, 0, 0),
          Array(0, 0, 0, 0),
          Array(0, 0, 0, 0),
          Array(0, 0, 0, 0),
        )
      )
      val imageT = new Tensor(Tensor.flatten(image), Shape(4, 4, 4))
      val expected = Array(
        Array(
          Array(0, 4, 8, 0),
          Array(12, 16, 20, 0),
          Array(24, 28, 32, 0),
          Array(0, 0, 0, 0),
        ),
        Array(
          Array(1, 5, 9, 0),
          Array(13, 17, 21, 0),
          Array(25, 29, 33, 0),
          Array(0, 0, 0, 0),
        ),
        Array(
          Array(2, 6, 10, 0),
          Array(14, 18, 22, 0),
          Array(26, 30, 34, 0),
          Array(0, 0, 0, 0),
        ),
        Array(
          Array(3, 7, 11, 0),
          Array(15, 19, 23, 0),
          Array(27, 31, 35, 0),
          Array(0, 0, 0, 0),
        ),
      )
      val expectedT = new Tensor(Tensor.flatten(expected), Shape(4, 4, 4))
      val result    = imageT.transpose(Array(2, 0, 1))
      assert(result == expectedT)
    }
  }

  it should "pad then transpose" in {
    val image = Array(
      Array(
        Array(0, 1, 2, 3),
        Array(4, 5, 6, 7),
        Array(8, 9, 10, 11),
      ),
      Array(
        Array(12, 13, 14, 15),
        Array(16, 17, 18, 19),
        Array(20, 21, 22, 23),
      ),
      Array(
        Array(24, 25, 26, 27),
        Array(28, 29, 30, 31),
        Array(32, 33, 34, 35),
      )
    )
    val imageT = new Tensor(Tensor.flatten(image), Shape(3, 3, 4))
    val expected = Array(
      Array(
        Array(0, 4, 8, 0),
        Array(12, 16, 20, 0),
        Array(24, 28, 32, 0),
        Array(0, 0, 0, 0),
      ),
      Array(
        Array(1, 5, 9, 0),
        Array(13, 17, 21, 0),
        Array(25, 29, 33, 0),
        Array(0, 0, 0, 0),
      ),
      Array(
        Array(2, 6, 10, 0),
        Array(14, 18, 22, 0),
        Array(26, 30, 34, 0),
        Array(0, 0, 0, 0),
      ),
      Array(
        Array(3, 7, 11, 0),
        Array(15, 19, 23, 0),
        Array(27, 31, 35, 0),
        Array(0, 0, 0, 0),
      ),
    )
    val expectedT = new Tensor(Tensor.flatten(expected), Shape(4, 4, 4))
    val padResult = imageT.pad(0, Array((0, 1), (0, 1), (0, 0)))
//    println("padResult")
//    println(padResult)
    val transResult = padResult.transpose(Array(2, 0, 1))
//    println("transResult")
//    println(transResult)
    val result =
      imageT.pad(0, Array((0, 1), (0, 1), (0, 0))).transpose(Array(2, 0, 1))
//    println("result")
//    println(result)
    assert(result == expectedT)
  }

  it should "perform a convolution" in {
    // 4 input channels, 4 output channels
    // 3x3 image
    // 2x2 convolution
    val image: Array[Array[Array[BigInt]]] = Array(
      Array(
        Array(0, 1, 2, 3),
        Array(4, 5, 6, 7),
        Array(8, 9, 10, 11),
      ),
      Array(
        Array(12, 13, 14, 15),
        Array(16, 17, 18, 19),
        Array(20, 21, 22, 23),
      ),
      Array(
        Array(24, 25, 26, 27),
        Array(28, 29, 30, 31),
        Array(32, 33, 34, 35),
      )
    )
    val img = new Tensor(image.map(_.flatten).flatten, Shape(3, 3, 4))
    val filter: Array[Array[Array[Array[BigInt]]]] = Array(
      Array(
        Array(
          Array(36, 37, 38, 39),
          Array(40, 41, 42, 43),
          Array(44, 45, 46, 47),
          Array(48, 49, 50, 51),
        ),
        Array(
          Array(52, 53, 54, 55),
          Array(56, 57, 58, 59),
          Array(60, 61, 62, 63),
          Array(64, 65, 66, 67),
        ),
      ),
      Array(
        Array(
          Array(68, 69, 70, 71),
          Array(72, 73, 74, 75),
          Array(76, 77, 78, 79),
          Array(80, 81, 82, 83),
        ),
        Array(
          Array(84, 85, 86, 87),
          Array(88, 89, 90, 91),
          Array(92, 93, 94, 95),
          Array(96, 97, 98, 99),
        ),
      ),
    )
    val flt = new Tensor(
      filter.map(_.map(_.flatten).flatten).flatten,
      Shape(2, 2, 4, 4)
    )

    val expected = Array(
      Array(
        Array(11904, 12056, 12208, 12360),
        Array(16128, 16344, 16560, 16776),
        Array(8000, 8124, 8248, 8372),
      ),
      Array(
        Array(24576, 24920, 25264, 25608),
        Array(28800, 29208, 29616, 30024),
        Array(13568, 13788, 14008, 14228),
      ),
      Array(
        Array(11168, 11388, 11608, 11828),
        Array(12768, 13020, 13272, 13524),
        Array(5648, 5782, 5916, 6050),
      ),
    )
    val exp = new Tensor(expected.map(_.flatten).flatten, Shape(3, 3, 4))

    val result = Tensor.goldenConv(img, flt, BigInt(0))

    assert(result == exp)
  }

}

class MultiRangeSpec extends UnitSpec {
  behavior of "MultiRange"

  it should "work" in {
    {
      val mr = new MultiRange(2, 3, 4)
      val expected = Array(
        Array(0, 0, 0),
        Array(0, 0, 1),
        Array(0, 0, 2),
        Array(0, 0, 3),
        Array(0, 1, 0),
        Array(0, 1, 1),
        Array(0, 1, 2),
        Array(0, 1, 3),
        Array(0, 2, 0),
        Array(0, 2, 1),
        Array(0, 2, 2),
        Array(0, 2, 3),
        Array(1, 0, 0),
        Array(1, 0, 1),
        Array(1, 0, 2),
        Array(1, 0, 3),
        Array(1, 1, 0),
        Array(1, 1, 1),
        Array(1, 1, 2),
        Array(1, 1, 3),
        Array(1, 2, 0),
        Array(1, 2, 1),
        Array(1, 2, 2),
        Array(1, 2, 3),
      )

      val e = expected.iterator
      for (r <- mr) {
        val i = e.next()
        i.zip(r).map { case (a, b) => assert(a == b) }
      }
    }

    {
      val mr = new MultiRange(2, 3, 4)
      val expected = Array(
        Array(0, 0, 0),
        Array(0, 0, 1),
        Array(0, 0, 2),
        Array(0, 0, 3),
        Array(0, 1, 0),
        Array(0, 1, 1),
        Array(0, 1, 2),
        Array(0, 1, 3),
        Array(0, 2, 0),
        Array(0, 2, 1),
        Array(0, 2, 2),
        Array(0, 2, 3),
        Array(1, 0, 0),
        Array(1, 0, 1),
        Array(1, 0, 2),
        Array(1, 0, 3),
        Array(1, 1, 0),
        Array(1, 1, 1),
        Array(1, 1, 2),
        Array(1, 1, 3),
        Array(1, 2, 0),
        Array(1, 2, 1),
        Array(1, 2, 2),
        Array(1, 2, 3),
      )

      val e = expected.iterator
      for (i <- e) {
        val r = mr.next()
        i.zip(r).map { case (a, b) => assert(a == b) }
      }
    }
  }
}
