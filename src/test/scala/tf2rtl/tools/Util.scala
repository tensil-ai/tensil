package tf2rtl.tools

import java.io._
import scala.io.Source
import scala.reflect.ClassTag
import tf2rtl.ArchitectureDataType

object Util {
  def readResult(
      dataType: ArchitectureDataType,
      stream: DataInputStream,
      arraySize: Int,
      resultSize: Int
  ): Array[Float] = {
    var y = Array.fill(resultSize)(0f)

    for (i <- 0 until resultSize)
      y(i) = dataType.readFloatConst(stream)

    val extra       = if ((resultSize % arraySize) != 0) 1 else 0
    val alignedSize = (arraySize * ((resultSize / arraySize) + extra))

    for (_ <- 0 until alignedSize - resultSize)
      dataType.readFloatConst(stream)

    y
  }

  def writeArgs(
      dataType: ArchitectureDataType,
      stream: DataOutputStream,
      arraySize: Int,
      xs: Float*
  ): Unit = {
    for (i <- 0 until xs.size)
      dataType.writeFloatConst(xs(i), stream)

    for (i <- 0 until arraySize - xs.size)
      dataType.writeFloatConst(0f, stream)
  }

  def writeCsv(
      dataType: ArchitectureDataType,
      stream: DataOutputStream,
      arraySize: Int,
      fileName: String
  ): Unit = {
    val source = Source.fromFile(fileName)

    for (line <- source.getLines()) {
      val pixel = line.split(",").map(_.toFloat)

      assert(pixel.size == arraySize)

      for (x <- pixel)
        dataType.writeFloatConst(x, stream)
    }

    source.close()
  }

  def convertCsvToData(
      dataType: ArchitectureDataType,
      arraySize: Int,
      csvFileName: String,
      dataFileName: String,
  ): Unit = {
    val stream = new FileOutputStream(dataFileName)

    writeCsv(dataType, new DataOutputStream(stream), arraySize, csvFileName)

    stream.close()
  }

  def argMax(y: Array[Float]): Int = {
    var max  = y(0)
    var maxI = 0

    for (i <- 1 until y.length) {
      val yi = y(i)
      if (yi > max) {
        max = yi
        maxI = i
      }
    }

    maxI
  }
}
