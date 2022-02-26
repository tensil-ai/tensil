package tf2rtl.data

import scala.Numeric.Implicits._
import scala.reflect.ClassTag

object Conv {
  def plusEquals[T : Numeric : ClassTag](
      target: Array[Array[T]],
      arg: Array[Array[T]]
  ): Unit = {
    for {
      i <- target.indices
      j <- target(0).indices
    } {
      target(i)(j) += arg(i)(j)
    }
  }

  def conv2d[T : Numeric : ClassTag](
      input: Array[Array[Array[T]]],
      weights: Array[Array[Array[Array[T]]]]
  ): Array[Array[Array[T]]] = {

    val rows              = input(0).length
    val cols              = input(0)(0).length
    val filterRows        = weights(0)(0).length
    val filterCols        = weights(0)(0)(0).length
    val numInputChannels  = input.length
    val numOutputChannels = weights.length

    val output = Array.fill(
      numOutputChannels,
      rows - filterRows + 1,
      cols - filterCols + 1
    )(implicitly[Numeric[T]].zero)

    for {
      outputChannel <- 0 until numOutputChannels
      inputChannel  <- 0 until numInputChannels
    } {
      val filter = weights(outputChannel)(inputChannel)
      val image  = input(inputChannel)
      val result = convSingleChannel(image, filter)
      plusEquals(output(outputChannel), result)
    }

    output
  }

  def padSame[T : Numeric : ClassTag](
      input: Array[Array[Array[T]]],
      filter: (Int, Int),
      element: T
  ): Array[Array[Array[T]]] = {
    val channels = input.length
    Array.tabulate(channels)(i => padSame(input(i), filter, element))
  }

  case class Padding(top: Int, bottom: Int, left: Int, right: Int) {
    val vertical   = top + bottom
    val horizontal = left + right
  }

  def pad[T : Numeric : ClassTag](
      input: Array[Array[Array[T]]],
      padding: Padding,
      element: T
  ): Array[Array[Array[T]]] = {
    val channels = input.length
    Array.tabulate(channels)(i => pad(input(i), padding, element))
  }

  def pad[T : Numeric : ClassTag](
      input: Array[Array[T]],
      padding: Padding,
      element: T
  ): Array[Array[T]] = {
    val rows = input.length
    val cols = input(0).length

    val output =
      Array.fill(rows + padding.vertical, cols + padding.horizontal)(element)

    for {
      i <- 0 until rows
      j <- 0 until cols
    } {
      output(padding.top + i)(padding.left + j) = input(i)(j)
    }

    output
  }

  def padSame[T : Numeric : ClassTag](
      input: Array[Array[T]],
      filter: (Int, Int),
      element: T
  ): Array[Array[T]] = {
    val rows       = input.length
    val cols       = input(0).length
    val filterRows = filter._1
    val filterCols = filter._2

    val paddingVertical = filterRows - 1
    val paddingTop      = paddingVertical / 2
    val paddingBottom   = paddingVertical - paddingTop

    val paddingHorizontal = filterCols - 1
    val paddingLeft       = paddingHorizontal / 2
    val paddingRight      = paddingHorizontal - paddingLeft

    val output =
      Array.fill(rows + paddingHorizontal, cols + paddingVertical)(element)

    for {
      i <- 0 until rows
      j <- 0 until cols
    } {
      output(paddingTop + i)(paddingLeft + j) = input(i)(j)
    }

    output
  }

  def convSingleChannel[T : Numeric : ClassTag](
      input: Array[Array[T]],
      filter: Array[Array[T]]
  ): Array[Array[T]] = {
    val rows       = input.length
    val cols       = input(0).length
    val filterRows = filter.length
    val filterCols = filter(0).length

    val output = Array.fill(rows - filterRows + 1, cols - filterCols + 1)(
      implicitly[Numeric[T]].zero
    )

    for {
      row       <- 0 until rows - filterRows + 1
      col       <- 0 until cols - filterCols + 1
      filterRow <- 0 until filterRows
      filterCol <- 0 until filterCols
    } {
      output(row)(col) += filter(filterRow)(filterCol) * input(row + filterRow)(
        col + filterCol
      )
    }

    output
  }
}
