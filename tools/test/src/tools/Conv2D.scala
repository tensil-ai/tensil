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
  ): InputStream =
    new ByteArrayInputStream(prepareInputBytes(dataType, arraySize))

  def prepareInputBytes(
      dataType: ArchitectureDataType,
      arraySize: Int
  ): Array[Byte] = {
    val inputPrep           = new ByteArrayOutputStream()
    val inputPrepDataStream = new DataOutputStream(inputPrep)

    def writePixel(k: Int, l: Int): Unit = {
      for (i <- 0 until 4)
        dataType.writeFloatConst(i + (l + k * 3) * 4, inputPrepDataStream)
      for (i <- 0 until arraySize - 4)
        dataType.writeFloatConst(0f, inputPrepDataStream)
    }

    for (k <- 0 until 3; l <- 0 until 3)
      writePixel(k, l)

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
