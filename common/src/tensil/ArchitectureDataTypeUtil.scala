/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools

import java.io._
import scala.io.Source
import scala.reflect.ClassTag
import tensil.ArchitectureDataType

object ArchitectureDataTypeUtil {
  def readResult(
      dataType: ArchitectureDataType,
      dataStream: DataInputStream,
      arraySize: Int,
      resultSize: Int
  ): Array[Float] = {
    var y = Array.fill(resultSize)(0f)

    for (i <- 0 until resultSize)
      y(i) = dataType.readFloatConst(dataStream)

    val extra       = if ((resultSize % arraySize) != 0) 1 else 0
    val alignedSize = (arraySize * ((resultSize / arraySize) + extra))

    for (_ <- 0 until alignedSize - resultSize)
      dataType.readFloatConst(dataStream)

    y
  }

  def writeArgs(
      dataType: ArchitectureDataType,
      dataStream: DataOutputStream,
      arraySize: Int,
      xs: Float*
  ): Unit = {
    for (i <- 0 until xs.size)
      dataType.writeFloatConst(xs(i), dataStream)

    for (i <- 0 until arraySize - xs.size)
      dataType.writeFloatConst(0f, dataStream)
  }

  def writeFromCsv(
      dataType: ArchitectureDataType,
      dataStream: DataOutputStream,
      arraySize: Int,
      csvFileName: String
  ): Unit = {
    val source = Source.fromFile(csvFileName)

    for (line <- source.getLines()) {
      val pixel = line.split(",").map(_.toFloat)

      assert(pixel.size == arraySize)

      for (x <- pixel)
        dataType.writeFloatConst(x, dataStream)
    }

    source.close()
  }

  def readToCsv(
      dataType: ArchitectureDataType,
      dataStream: DataInputStream,
      arraySize: Int,
      size: Long,
      csvFileName: String
  ): Unit = {
    val csvStream = new DataOutputStream(new FileOutputStream(csvFileName))

    for (_ <- 0L until size) {
      for (i <- 0 until arraySize) {
        csvStream.writeBytes(s"${dataType.readFloatConst(dataStream)}")

        if (i != arraySize - 1)
          csvStream.writeBytes(",")
      }

      csvStream.writeBytes("\r\n")
    }

    csvStream.close()
  }

  def convertCsvToData(
      dataType: ArchitectureDataType,
      arraySize: Int,
      csvFileName: String,
      dataFileName: String,
  ): Unit = {
    val stream = new FileOutputStream(dataFileName)

    writeFromCsv(dataType, new DataOutputStream(stream), arraySize, csvFileName)

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
