/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools

import java.io._
import scala.reflect.ClassTag
import scala.io.Source
import tensil.tools.emulator.{Emulator, ExecutiveTraceContext}
import tensil.ArchitectureDataType

object ResNet50 {
  def prepareInputStream(
      dataType: ArchitectureDataType,
      arraySize: Int,
      count: Int
  ): InputStream = {
    val fileName = s"./models/data/resnet_input_${count}x224x224x${arraySize}.csv"

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

  private val GoldenClasses: Array[Int] = Array(
    386, // African_elephant
    248, // Eskimo_dog
    285  // Egyptian_cat
  )

  def assertOutput(
      dataType: ArchitectureDataType,
      arraySize: Int,
      bytes: Array[Byte],
      count: Int
  ): Unit = {
    val output =
      new DataInputStream(new ByteArrayInputStream(bytes))

    for (i <- 0 until count) {
      assert(
        ArchitectureDataTypeUtil.argMax(
          ArchitectureDataTypeUtil.readResult(dataType, output, arraySize, 1000)
        ) == GoldenClasses(i)
      )
    }
  }
}
