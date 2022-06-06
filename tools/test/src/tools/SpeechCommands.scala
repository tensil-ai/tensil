/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools

import java.io._
import scala.reflect.ClassTag
import scala.io.Source
import tensil.tools.emulator.{Emulator, ExecutiveTraceContext}
import tensil.ArchitectureDataType

object SpeechCommands {
  def prepareInputStream(
      dataType: ArchitectureDataType,
      arraySize: Int,
      count: Int
  ): InputStream = {
    val fileName = s"./models/data/speech_commands_input_${count}x${arraySize}.csv"

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

  val ClassSize = 8
  val GoldenClasses: Array[Int] = Array(
    7, 2, 4, 6, 1, 6, 6, 5, 0, 5
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
      val expected = GoldenClasses(i)
      val actual = ArchitectureDataTypeUtil.argMax(
        ArchitectureDataTypeUtil.readResult(dataType, output, arraySize, ClassSize)
      )

      println(s"expected=$expected, actual=$actual")
      assert(expected == actual)
    }
  }
}
