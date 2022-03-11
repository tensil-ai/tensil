/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools

import java.io._
import scala.reflect.ClassTag
import tensil.tools.golden.{Processor, ExecutiveTraceContext}
import scala.collection.mutable
import tensil.{Architecture, ArchitectureDataType, ArchitectureDataTypeWithBase}
import tensil.tools.model.Model
import tensil.tools.golden.ExecutiveTrace

object GoldenProcessorHelper {
  def test(
      modelName: String,
      inputBatchSize: Int = 1,
      traceContext: ExecutiveTraceContext = ExecutiveTraceContext.default
  ): Unit = {
    val modelStream = new FileInputStream(s"$modelName.tmodel")
    val model       = upickle.default.read[Model](modelStream)

    model.arch.dataType.name match {
      case ArchitectureDataType.FLOAT32.name =>
        doTest(
          ArchitectureDataType.FLOAT32,
          model,
          inputBatchSize,
          traceContext
        )

      case ArchitectureDataType.FP32BP16.name =>
        doTest(
          ArchitectureDataType.FP32BP16,
          model,
          inputBatchSize,
          traceContext
        )

      case ArchitectureDataType.FP18BP10.name =>
        doTest(
          ArchitectureDataType.FP18BP10,
          model,
          inputBatchSize,
          traceContext
        )

      case ArchitectureDataType.FP16BP8.name =>
        doTest(
          ArchitectureDataType.FP16BP8,
          model,
          inputBatchSize,
          traceContext
        )

      case ArchitectureDataType.FP8BP4.name =>
        doTest(
          ArchitectureDataType.FP8BP4,
          model,
          inputBatchSize,
          traceContext
        )
    }
  }

  val yoloPrefix = "yolov4_tiny_([0-9]+)".r

  private def prepareInputStream(
      modelName: String,
      dataType: ArchitectureDataType,
      arraySize: Int,
      count: Int = 1
  ): InputStream =
    if (modelName.startsWith("xor"))
      Xor.prepareInputStream(dataType, arraySize, count)
    else if (modelName.startsWith("mlp_mnist"))
      Mnist.prepareInputStream(arraySize, count, false)
    else if (modelName.startsWith("maxpool"))
      MaxPool.prepareInputStream(dataType, arraySize)
    else if (modelName.startsWith("conv2d"))
      Conv2D.prepareInputStream(dataType, arraySize)
    else if (modelName.startsWith("cnn_mnist"))
      Mnist.prepareInputStream(arraySize, count, true)
    else if (modelName.startsWith("resnet20v2"))
      ResNet.prepareInputStream(dataType, arraySize, count)
    else if (modelName.startsWith("resnet50v2"))
      ResNet50.prepareInputStream(dataType, arraySize, count)
    else if (yoloPrefix.findFirstIn(modelName).isDefined) {
      val yoloPrefix(yoloSize) = modelName
      new TinyYolo(yoloSize.toInt, modelName.endsWith("onnx"))
        .prepareInputStream(
          dataType,
          arraySize,
          count
        )
    } else
      throw new IllegalArgumentException()

  private def assertOutput(
      modelName: String,
      outputName: String,
      dataType: ArchitectureDataType,
      arraySize: Int,
      bytes: Array[Byte],
      count: Int = 1
  ): Unit =
    if (modelName.startsWith("xor"))
      Xor.assertOutput(dataType, arraySize, bytes, count)
    else if (modelName.startsWith("maxpool"))
      MaxPool.assertOutput(dataType, arraySize, bytes)
    else if (modelName.matches("conv2d_4x4_valid((_tiled)|(_oversized))?"))
      Conv2D.assertOutput(dataType, arraySize, bytes, Conv2D.ValidStride1Pixels)
    else if (
      modelName.matches("conv2d_4x4_valid_stride_2((_tiled)|(_oversized))?")
    )
      Conv2D.assertOutput(dataType, arraySize, bytes, Conv2D.ValidStride2Pixels)
    else if (modelName.matches("conv2d_4x4_same((_tiled)|(_oversized))?"))
      Conv2D.assertOutput(dataType, arraySize, bytes, Conv2D.SameStride1Pixels)
    else if (
      modelName.matches("conv2d_4x4_same_stride_2((_tiled)|(_oversized))?")
    )
      Conv2D.assertOutput(dataType, arraySize, bytes, Conv2D.SameStride2Pixels)
    else if (modelName.matches("conv2d_4x4_valid((_tiled)|(_oversized))?"))
      Conv2D.assertOutput(dataType, arraySize, bytes, Conv2D.ValidStride1Pixels)
    else if (modelName.matches("conv2d_4x4_valid((_tiled)|(_oversized))?"))
      Conv2D.assertOutput(dataType, arraySize, bytes, Conv2D.ValidStride1Pixels)
    else if (modelName == "conv2d_4x4_same_relu_2x2_maxpool_valid_stride_2")
      Conv2D.assertOutput(
        dataType,
        arraySize,
        bytes,
        Conv2D.SameReluMaxPoolValidStride2Pixels
      )
    else if (modelName == "conv2d_4x4_same_relu_2x2_maxpool_valid_stride_1")
      Conv2D.assertOutput(
        dataType,
        arraySize,
        bytes,
        Conv2D.SameReluMaxPoolValidStride1Pixels
      )
    else if (
      modelName.startsWith("mlp_mnist") || modelName.startsWith("cnn_mnist")
    )
      Mnist.assertOutput(dataType, arraySize, bytes, count)
    else if (modelName.startsWith("resnet20v2"))
      ResNet.assertOutput(dataType, arraySize, bytes, count)
    else if (modelName.startsWith("resnet50v2"))
      ResNet50.assertOutput(dataType, arraySize, bytes, count)
    else if (yoloPrefix.findFirstIn(modelName).isDefined) {
      val yoloPrefix(yoloSize) = modelName
      new TinyYolo(yoloSize.toInt, modelName.endsWith("onnx"))
        .assertOutput(outputName, dataType, arraySize, bytes)
    } else
      throw new IllegalArgumentException()

  private def minimumInputCount(modelName: String): Int =
    if (modelName.startsWith("xor"))
      4
    else if (modelName.startsWith("resnet50v2"))
      3
    else if (modelName.startsWith("resnet20v2"))
      10
    else
      1

  private def doTest[T : Numeric : ClassTag](
      dataType: ArchitectureDataTypeWithBase[T],
      model: Model,
      inputBatchSize: Int,
      traceContext: ExecutiveTraceContext
  ): Unit = {
    val processor = new Processor(
      dataType = dataType,
      arch = model.arch
    )

    processor.writeDRAM1(
      model.consts(0).size,
      new FileInputStream(model.consts(0).fileName)
    )

    val outputs = mutable.Map.empty[String, ByteArrayOutputStream]
    val count   = Math.max(inputBatchSize, minimumInputCount(model.name))

    val input = model.inputs(0)
    val inputStream =
      prepareInputStream(model.name, dataType, model.arch.arraySize, count)

    for (_ <- 0 until count / inputBatchSize) {
      processor.writeDRAM0(
        input.base until input.base + input.size,
        new DataInputStream(inputStream)
      )

      var trace = new ExecutiveTrace(traceContext)

      processor.run(new FileInputStream(model.program.fileName), trace)

      trace.printTrace()

      for (output <- model.outputs) {
        val outputStream =
          outputs.getOrElseUpdate(output.name, new ByteArrayOutputStream())
        processor.readDRAM0(
          output.base until output.base + output.size,
          new DataOutputStream(outputStream)
        )
      }
    }

    for ((name, outputStream) <- outputs)
      assertOutput(
        model.name,
        name,
        dataType,
        model.arch.arraySize,
        outputStream.toByteArray,
        count
      )
  }
}
