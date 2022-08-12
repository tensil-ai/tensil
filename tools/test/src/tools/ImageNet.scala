/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools

import java.io._
import scala.reflect.ClassTag
import scala.io.Source
import tensil.tools.emulator.{Emulator, ExecutiveTraceContext}
import tensil.ArchitectureDataType

object ImageNet {
  def prepareInputStream(
      dataType: ArchitectureDataType,
      arraySize: Int,
      count: Int
  ): InputStream = {
    val fileName = s"./models/data/imagenet_${count}x${arraySize}.csv"

    val inputPrep           = new ByteArrayOutputStream()
    val inputPrepDataStream = new DataOutputStream(inputPrep)

    ArchitectureDataTypeUtil.writeFromCsv(
      dataType,
      inputPrepDataStream,
      arraySize,
      fileName
    )

    new ByteArrayInputStream(inputPrep.toByteArray())
  }

  def assertOutput(
      dataType: ArchitectureDataType,
      arraySize: Int,
      bytes: Array[Byte],
      count: Int,
      goldenClasses: Seq[Int]
  ): Unit = {
    require(count == goldenClasses.size)

    val output =
      new DataInputStream(new ByteArrayInputStream(bytes))

    for (i <- 0 until count) {
      val expected = goldenClasses(i)
      val actual = ArchitectureDataTypeUtil.argMax(
        ArchitectureDataTypeUtil.readResult(dataType, output, arraySize, 1000)
      )

      println(s"expected=$expected, actual=$actual")
      assert(actual == expected)
    }
  }
}
