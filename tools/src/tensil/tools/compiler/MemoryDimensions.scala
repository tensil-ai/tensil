/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import tensil.tools.CompilerException
import tensil.tools.util

object MemoryDimensions {
  val NumberCode      = "N"
  val HeightCode      = "H"
  val WidthCode       = "W"
  val ChannelsCode    = "C"
  val ChannelsOutCode = "Co"

  def apply(
      arraySize: Int,
      modelDimensions: String,
      layoutDimensions: String,
      isWeights: Boolean,
      dimensions: Vector[Int]
  ): MemoryDimensions = {
    val parsedModelDimensions =
      parseDimensions(modelDimensions)

    var currentNotFoundIndex = 0
    def nextNotFoundIndex() = {
      currentNotFoundIndex -= 1
      currentNotFoundIndex
    }

    val numberIndex =
      indexOfCode(parsedModelDimensions, NumberCode, nextNotFoundIndex)
    val heightIndex =
      indexOfCode(parsedModelDimensions, HeightCode, nextNotFoundIndex)
    val widthIndex =
      indexOfCode(parsedModelDimensions, WidthCode, nextNotFoundIndex)
    val channelsIndex =
      indexOfCode(parsedModelDimensions, ChannelsCode, nextNotFoundIndex)
    val channelsOutIndex =
      indexOfCode(parsedModelDimensions, ChannelsOutCode, nextNotFoundIndex)

    val parsedLayoutDimensions = parseDimensions(layoutDimensions)
    val layout =
      parsedLayoutDimensions.map(parsedModelDimensions.indexOf(_))

    new MemoryDimensions(
      arraySize,
      numberIndex,
      heightIndex,
      widthIndex,
      channelsIndex,
      channelsOutIndex,
      layout,
      isWeights,
      dimensions
    )
  }

  private def indexOfCode(
      parsedDimensions: Seq[String],
      code: String,
      notFoundIndex: () => Int
  ): Int = {
    val i = parsedDimensions.indexOf(code)

    if (i == -1) notFoundIndex() else i
  }

  private def parseDimensions(dimensions: String) =
    "([A-Z][a-z]*)".r.findAllMatchIn(dimensions).map(_.toString()).toVector
}

class MemoryDimensions private (
    arraySize: Int,
    numberIndex: Int,
    heightIndex: Int,
    widthIndex: Int,
    channelsIndex: Int,
    channelsOutIndex: Int,
    layout: Vector[Int],
    isWeights: Boolean,
    dimensions: Vector[Int]
) {
  private def isPermutationSeq(v: Seq[Int], n: Int) =
    v.distinct.size == v.size && v.size == n && v.max - v.min == n - 1

  private def checkRequirements() = {
    val dimensionIndexes =
      Seq(numberIndex, heightIndex, widthIndex, channelsIndex, channelsOutIndex)

    require(isPermutationSeq(dimensionIndexes, 5))
    require(isPermutationSeq(dimensionIndexes.filter(_ >= 0), dimensions.size))
    require(isPermutationSeq(layout, dimensions.size))
  }

  def transform(transformation: Seq[Int]): MemoryDimensions = {
    require(isPermutationSeq(transformation, order))

    val transformedDimensions = transformation.map(dimensions(_)).toVector

    def transformIndex(index: Int) =
      if (index >= 0)
        transformation.indexOf(index)
      else
        index

    val transformedLayout = layout.map(transformIndex(_))

    new MemoryDimensions(
      arraySize = arraySize,
      numberIndex = transformIndex(numberIndex),
      heightIndex = transformIndex(heightIndex),
      widthIndex = transformIndex(widthIndex),
      channelsIndex = transformIndex(channelsIndex),
      channelsOutIndex = transformIndex(channelsOutIndex),
      transformedLayout,
      isWeights,
      transformedDimensions
    )
  }

  checkRequirements()

  def order           = dimensions.size
  def modelDimensions = dimensions

  private val dimensionNames = Map(
    numberIndex      -> MemoryDimensions.NumberCode,
    heightIndex      -> MemoryDimensions.HeightCode,
    widthIndex       -> MemoryDimensions.WidthCode,
    channelsIndex    -> MemoryDimensions.ChannelsCode,
    channelsOutIndex -> MemoryDimensions.ChannelsOutCode
  )

  private def at(i: Int) =
    if (i >= 0 && i < order) dimensions(i)
    else
      throw new CompilerException(
        s"Memory has no ${dimensionNames(i)} dimension"
      )

  def number      = at(numberIndex)
  def height      = at(heightIndex)
  def width       = at(widthIndex)
  def channels    = at(channelsIndex)
  def channelsOut = at(channelsOutIndex)

  def channelsIn = channels

  def lastInLayout = at(layout.last)

  def modelOffset(
      width: Int
  ): Int = {
    require(order == 1)

    require(numberIndex < 0)
    require(heightIndex < 0)
    require(widthIndex >= 0)
    require(channelsIndex < 0)
    require(channelsOutIndex < 0)

    width
  }

  def modelOffset(
      height: Int,
      width: Int
  ): Int = {
    require(order == 2)

    require(numberIndex < 0)
    require(heightIndex >= 0)
    require(widthIndex >= 0)
    require(channelsIndex < 0)
    require(channelsOutIndex < 0)

    val pos = Array.fill(2)(0)
    pos(heightIndex) = height
    pos(widthIndex) = width

    pos(0) * dimensions(1) + pos(1)
  }

  def modelOffset(
      height: Int,
      width: Int,
      channelsIn: Int,
      channelsOut: Int
  ): Int = {
    require(order == 4)

    require(numberIndex < 0)
    require(heightIndex >= 0)
    require(widthIndex >= 0)
    require(channelsIndex >= 0)
    require(channelsOutIndex >= 0)

    val pos = Array.fill(4)(0)
    pos(heightIndex) = height
    pos(widthIndex) = width
    pos(channelsIndex) = channelsIn
    pos(channelsOutIndex) = channelsOut

    ((pos(0) * dimensions(1) + pos(1)) * dimensions(2) + pos(2)) * dimensions(
      3
    ) + pos(3)
  }

  def modelDimensionsName =
    dimensionNames.toSeq.filter(_._1 >= 0).sortBy(_._1).map(_._2).mkString("")
  def layoutDimensionsName = layout.map(dimensionNames(_)).mkString("")

  private def atVectors(i: Int) = {
    val dimension   = at(i)
    val layoutIndex = layout.indexOf(i)
    if (layoutIndex == order - 1)
      util.divCeil(dimension, arraySize)
    else if (isWeights && order >= 2 && layoutIndex == order - 2)
      // For 2D+ (e.g. HW) weights the second from last
      // dimension (e.g. height) must be aligned on array size
      (util.divCeil(dimension, arraySize) * arraySize)
    else
      dimension
  }

  def numberVectors      = atVectors(numberIndex)
  def heightVectors      = atVectors(heightIndex)
  def widthVectors       = atVectors(widthIndex)
  def channelsVectors    = atVectors(channelsIndex)
  def channelsOutVectors = atVectors(channelsOutIndex)

  def channelsInVectors = channelsVectors

  def lastInLayoutVectors = atVectors(layout.last)

  val sizeVectors = List.tabulate(order)(atVectors(_)).product
  val sizeScalars = dimensions.product

  override def toString() =
    s"$modelDimensionsName[${modelDimensions.mkString(",")}]=$layoutDimensionsName[${layoutDimensionsVectors
      .mkString(",")}]=${sizeVectors}*${arraySize}"

  def layoutDimensions        = layout.map(at(_))
  def layoutDimensionsVectors = layout.map(atVectors(_))

  def vectorIndexOffsetAt(i: Int) = {
    val (_, _, vectorIndex, vectorOffset) = lastAndVectorIndexOffsetAt(i)
    (vectorIndex, vectorOffset)
  }

  def lastAndVectorIndexOffsetAt(i: Int) = {
    val lastIndex  = i / lastInLayout
    val lastOffset = i % lastInLayout

    val vectorIndex =
      (lastIndex * lastInLayoutVectors) + (lastOffset / arraySize)

    val vectorOffset = lastOffset % arraySize

    (lastIndex, lastOffset, vectorIndex, vectorOffset)
  }
}
