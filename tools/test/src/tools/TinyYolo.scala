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
  val GoldenOutputFileNames =
    if (onnx)
      Map(
        "model/conv2d_17/BiasAdd:0" -> s"./models/data/yolov4_tiny_output_${yoloSize}x${yoloSize}x${arraySize}_conv17.csv",
        "model/conv2d_20/BiasAdd:0" -> s"./models/data/yolov4_tiny_output_${yoloSize}x${yoloSize}_conv20.csv"
      )
    else
      Map(
        "model/conv2d_17/BiasAdd" -> s"./models/data/yolov4_tiny_output_${yoloSize}x${yoloSize}x${arraySize}_conv17.csv",
        "model/conv2d_20/BiasAdd" -> s"./models/data/yolov4_tiny_output_${yoloSize}x${yoloSize}x${arraySize}_conv20.csv"
      )

  def assertOutput(
      outputName: String,
      dataType: ArchitectureDataType,
      arraySize: Int,
      bytes: Array[Byte],
  ): Unit = {
    val rmse   = new RMSE(printValues = false)
    val source = Source.fromFile(GoldenOutputFileNames(outputName))
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
