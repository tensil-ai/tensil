/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools

import java.io._
import scala.reflect.ClassTag
import tensil.tools.emulator.{Emulator, ExecutiveTraceContext}
import scala.collection.mutable
import scala.io.Source
import tensil.ArchitectureDataType

case class TinyYolo(yoloSize: Int, onnx: Boolean) {
  def ConvFileName(n: Int, arraySize: Int) =
    s"./models/data/yolov4_tiny_output_${yoloSize}x${yoloSize}x${arraySize}_conv${n}.csv"

  val GoldenOutputFileNames =
    if (onnx)
      Map(
        "model/conv2d_17/BiasAdd:0" -> ((arraySize: Int) =>
          ConvFileName(17, arraySize)
        ),
        "model/conv2d_20/BiasAdd:0" -> ((arraySize: Int) =>
          ConvFileName(20, arraySize)
        )
      )
    else
      Map(
        "model/conv2d_17/BiasAdd" -> ((arraySize: Int) =>
          ConvFileName(17, arraySize)
        ),
        "model/conv2d_20/BiasAdd" -> ((arraySize: Int) =>
          ConvFileName(20, arraySize)
        )
      )

  def assertOutput(
      outputName: String,
      dataType: ArchitectureDataType,
      arraySize: Int,
      bytes: Array[Byte],
  ): Unit = {
    val rmse   = new RMSE(printValues = false)
    val source = Source.fromFile(GoldenOutputFileNames(outputName)(arraySize))
    val output =
      new DataInputStream(new ByteArrayInputStream(bytes))

    for (line <- source.getLines()) {
      val goldenPixel = line.split(",").map(_.toFloat)
      val pixel =
        ArchitectureDataTypeUtil.readResult(
          dataType,
          output,
          arraySize,
          goldenPixel.size
        )

      for (i <- 0 until goldenPixel.size)
        rmse.addSample(pixel(i), goldenPixel(i))
    }

    source.close()
    assert(rmse.compute < 0.6)
  }

  def prepareInputStream(
      dataType: ArchitectureDataType,
      arraySize: Int,
      count: Int
  ): InputStream = {
    val fileName =
      s"./models/data/yolo_input_${count}x${yoloSize}x${yoloSize}x${arraySize}.csv"

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
}
