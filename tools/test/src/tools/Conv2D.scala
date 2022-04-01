/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools

import java.io._
import scala.reflect.ClassTag
import tensil.tools.golden.{Processor, ExecutiveTraceContext}
import tensil.ArchitectureDataType

object Conv2D {
  def prepareInputStream(
      dataType: ArchitectureDataType,
      arraySize: Int,
      height: Int,
      width: Int
  ): InputStream =
    new ByteArrayInputStream(prepareInputBytes(dataType, arraySize, height, width))

  def prepareInputBytes(
      dataType: ArchitectureDataType,
      arraySize: Int,
      height: Int,
      width: Int
  ): Array[Byte] = {
    val inputPrep           = new ByteArrayOutputStream()
    val inputPrepDataStream = new DataOutputStream(inputPrep)

    def writePixel(base: Int): Unit = {
      for (i <- 0 until 4)
        dataType.writeFloatConst(i + base * 4, inputPrepDataStream)
      for (i <- 0 until arraySize - 4)
        dataType.writeFloatConst(0f, inputPrepDataStream)
    }

    for (k <- 0 until height; l <- 0 until width)
      writePixel(l + k * width)

    inputPrep.toByteArray()
  }

  val ValidStride2Pixels = Array(
    Array(
      Array(11904.0f, 12056.0f, 12208.0f, 12360.0f),
    )
  )

  val ValidStride1Pixels = Array(
    Array(
      Array(11904.0f, 12056.0f, 12208.0f, 12360.0f),
      Array(16128.0f, 16344.0f, 16560.0f, 16776.0f)
    ),
    Array(
      Array(24576.0f, 24920.0f, 25264.0f, 25608.0f),
      Array(28800.0f, 29208.0f, 29616.0f, 30024.0f)
    )
  )

  val SameStride2Pixels = Array(
    Array(
      Array(11904.0f, 12056.0f, 12208.0f, 12360.0f),
      Array(8000.0f, 8124.0f, 8248.0f, 8372.0f)
    ),
    Array(
      Array(11168.0f, 11388.0f, 11608.0f, 11828.0f),
      Array(5648.0f, 5782.0f, 5916.0f, 6050.0f)
    )
  );

  val SameStride1Pixels = Array(
    Array(
      Array(11904.0f, 12056.0f, 12208.0f, 12360.0f),
      Array(16128.0f, 16344.0f, 16560.0f, 16776.0f),
      Array(8000.0f, 8124.0f, 8248.0f, 8372.0f)
    ),
    Array(
      Array(24576.0f, 24920.0f, 25264.0f, 25608.0f),
      Array(28800.0f, 29208.0f, 29616.0f, 30024.0f),
      Array(13568.0f, 13788.0f, 14008.0f, 14228.0f)
    ),
    Array(
      Array(11168.0f, 11388.0f, 11608.0f, 11828.0f),
      Array(12768.0f, 13020.0f, 13272.0f, 13524.0f),
      Array(5648.0f, 5782.0f, 5916.0f, 6050.0f)
    )
  )

  val SameReluMaxPoolValidStride2Pixels = Array(
    Array(Array(28800.0f, 29208.0f, 29616.0f, 30024.0f))
  )

  val SameReluMaxPoolValidStride1Pixels = Array(
    Array(
      Array(28800.0f, 29208.0f, 29616.0f, 30024.0f),
      Array(28800.0f, 29208.0f, 29616.0f, 30024.0f)
    ),
    Array(
      Array(28800.0f, 29208.0f, 29616.0f, 30024.0f),
      Array(28800.0f, 29208.0f, 29616.0f, 30024.0f)
    )
  );

  val ValidNonSquareStride1Pixels = Array(
    Array(
      Array(17152.0f, 17368.0f, 17584.0f, 17800.0f),
      Array(21376.0f, 21656.0f, 21936.0f, 22216.0f),
      Array(25600.0f, 25944.0f, 26288.0f, 26632.0f),
      Array(29824.0f, 30232.0f, 30640.0f, 31048.0f)
    ),
    Array(
      Array(38272.0f, 38808.0f, 39344.0f, 39880.0f),
      Array(42496.0f, 43096.0f, 43696.0f, 44296.0f),
      Array(46720.0f, 47384.0f, 48048.0f, 48712.0f),
      Array(50944.0f, 51672.0f, 52400.0f, 53128.0f)
    )
  )

  val SameNonSquareStride1Pixels = Array(
    Array(
      Array(17152.0f, 17368.0f, 17584.0f, 17800.0f),
      Array(21376.0f, 21656.0f, 21936.0f, 22216.0f),
      Array(25600.0f, 25944.0f, 26288.0f, 26632.0f),
      Array(29824.0f, 30232.0f, 30640.0f, 31048.0f),
      Array(14080.0f, 14300.0f, 14520.0f, 14740.0f)
    ),
    Array(
      Array(38272.0f, 38808.0f, 39344.0f, 39880.0f),
      Array(42496.0f, 43096.0f, 43696.0f, 44296.0f),
      Array(46720.0f, 47384.0f, 48048.0f, 48712.0f),
      Array(50944.0f, 51672.0f, 52400.0f, 53128.0f),
      Array(23360.0f, 23740.0f, 24120.0f, 24500.0f)
    ),
    Array(
      Array(17568.0f, 17916.0f, 18264.0f, 18612.0f),
      Array(19168.0f, 19548.0f, 19928.0f, 20308.0f),
      Array(20768.0f, 21180.0f, 21592.0f, 22004.0f),
      Array(22368.0f, 22812.0f, 23256.0f, 23700.0f),
      Array(9680.0f, 9910.0f, 10140.0f, 10370.0f)
    )
  )

  def assertOutput(
      dataType: ArchitectureDataType,
      arraySize: Int,
      bytes: Array[Byte],
      expectedPixels: Array[Array[Array[Float]]]
  ): Unit = {
    val output =
      new DataInputStream(new ByteArrayInputStream(bytes))

    val rmse = new RMSE()

    for (
      k <- 0 until expectedPixels.length; l <- 0 until expectedPixels(0).length
    ) {
      val pixel = Util.readResult(dataType, output, arraySize, 4)
      for (i <- 0 until 4)
        rmse.addSample(pixel(i), expectedPixels(k)(l)(i))
    }

    assert(rmse.compute < dataType.error)
  }
}
