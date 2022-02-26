package tf2rtl.tools

import java.io._
import scala.reflect.ClassTag
import tf2rtl.data.{Shape, Tensor}
import tf2rtl.tools.golden.{Processor, ExecutiveTraceContext}
import tf2rtl.ArchitectureDataType

object MaxPool {
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
      for (i <- 0 until 5) {
        val x = i + (l + k * 22) * 5
        dataType.writeFloatConst(x, inputPrepDataStream)
      }
      for (i <- 0 until arraySize - 5) {
        dataType.writeFloatConst(0f, inputPrepDataStream)
      }
    }

    for (k <- 0 until 22; l <- 0 until 22)
      writePixel(k, l)

    inputPrep.toByteArray()
  }

  def assertOutput(
      dataType: ArchitectureDataType,
      arraySize: Int,
      outputBytes: Array[Byte]
  ): Unit = {
    val output =
      new DataInputStream(new ByteArrayInputStream(outputBytes))

    val floats = Array.fill[Float](22 * 22 * 8)(0f)

    for (k <- 0 until 22; l <- 0 until 22; i <- 0 until 5) {
      floats(i + (l + k * 22) * 8) = i + (l + k * 22) * 5
    }

    val t = new Tensor(
      floats,
      Shape(22, 22, 8)
    )

    val r              = Tensor.goldenMaxPool(t, 2, 2, 2)
    val expectedPixels = r.to3D()
    val rmse           = new RMSE()

    for (
      k <- 0 until expectedPixels.length; l <- 0 until expectedPixels(0).length
    ) {
      val pixel = Util.readResult(dataType, output, arraySize, 5)

      for (i <- 0 until 5)
        rmse.addSample(pixel(i), expectedPixels(k)(l)(i))
    }

    assert(rmse.compute < dataType.error)
  }
}
