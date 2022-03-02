/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.data

import chisel3._
import chisel3.experimental.FixedPoint

import java.io.InputStream
import java.nio.ByteBuffer
import scala.Numeric.Implicits._
import scala.reflect.ClassTag

class Tensor[T : Numeric : ClassTag](
    private val data: Array[T],
    val shape: Shape
) {
  if (data.length != shape.arraySize) {
    throw new Tensor.ShapeDataMismatchException(shape, data.length)
  }

  def apply(index: Index): Tensor[T] = {
    val p                 = new CartesianProduct(index.asArrays(shape): _*)
    val newData: Array[T] = (for (idx <- p) yield data(offset(idx))).toArray
    new Tensor[T](newData, index.shape(shape))
  }

  def map[S : Numeric : ClassTag](fn: T => S): Tensor[S] = {
    new Tensor(data.map(fn), shape)
  }

  def get(index: Array[Int]): T = {
    data(offset(index))
  }

  def set(index: Array[Int], value: T): Unit = {
    data(offset(index)) = value
  }

  def set[S <: Data with Num[S]](index: Array[Int], value: S): Unit = {
    data(offset(index)) = Tensor.chiselTypeAsLit(value)
  }

  private def offset(index: Array[Int]): Int = {
    Tensor.offset(shape, index)
  }

  private class Pad(shape: Shape, padding: Array[(Int, Int)]) {
    val newShape = Shape(
      (for ((shp, (before, after)) <- shape.zip(padding))
        yield shp + before + after).toArray
    )

    def offset(index: Array[Int]): Int = {
      var ot = index(index.length - 1)
      for (i <- 0 until newShape.length - 1) {
        ot = ot + (index(i) + padding(i)._1) * newShape
          .slice(i + 1, newShape.length)
          .product
      }
      ot
    }
  }

  def pad(element: T, dimension: Array[(Int, Int)]): Tensor[T] = {
    if (dimension.length != shape.length) {
      throw new Exception("wrong number of dimensions given")
    }
    for (dim <- dimension) {
      if (dim._1 < 0 || dim._2 < 0) {
        throw new Exception("padding amount must be 0 or greater")
      }
    }
    val pad     = new Pad(shape, dimension)
    val newData = Array.fill(pad.newShape.arraySize)(element)
    val prod = new CartesianProduct(
      shape.map(x => (0 until x).toArray).toArray: _*
    )
    for (idx <- prod) {
      newData(pad.offset(idx)) = data(offset(idx))
    }
    new Tensor(newData, pad.newShape)
  }

  def address(index: Index, base: Int = 0, pageSize: Int = 1): Array[Int] = {
    val p = new CartesianProduct(index.asArrays(shape): _*)
    (for (idx <- p) yield base + (offset(idx) / pageSize)).toArray
  }

  def transpose(): Tensor[T] = {
    if (shape.length != 2) {
      throw new Exception(
        "transpose (with no arguments) only works on 2-dimensional tensors"
      )
    }
    val result = Array.fill(data.length)(data(0))
    for (i <- 0 until shape(0)) {
      for (j <- 0 until shape(1)) {
        val originalIndex = offset(Array(i, j))
        val newIndex      = offset(Array(j, i))
        result(newIndex) = data(originalIndex)
      }
    }
    new Tensor(result, Shape(shape(1), shape(0)))
  }

  class Permutation(positions: Array[Int]) {
    def permute[S : ClassTag](arr: Seq[S]): Array[S] = {
      if (arr.length != positions.length) {
        throw new Exception(
          "must supply an element for every position in the permutation"
        )
      }
      if (arr.isEmpty) {
        arr.toArray
      } else {
        positions.map(i => arr(i))
      }
    }
  }

  def transpose(axes: Array[Int]): Tensor[T] = {
    if (axes.length != shape.length) {
      throw new Exception(
        "must supply a new axis position for every axis in the original shape"
      )
    }
    val perm     = new Permutation(axes)
    val result   = Array.fill(data.length)(data(0))
    val newShape = Shape(perm.permute(shape.toSeq))
    val oldIndex = Index(shape.map(len => Right(Slice(0, len))).toArray)
    val pOld     = new CartesianProduct(oldIndex.asArrays(shape): _*)
    for (iOld <- pOld) {
      val iNew = perm.permute(iOld)
      result(Tensor.offset(newShape, iNew)) = data(offset(iOld))
    }
    new Tensor(result, newShape)
  }

  def max: T = {
    data.max
  }

  def reshape(shp: Shape): Tensor[T] = {
    if (shp.arraySize != shape.arraySize) {
      throw new Exception(
        "new shape must have the same total number of elements as old shape"
      )
    }
    new Tensor(data, shp)
  }

  def mustNotBeEmpty(): Unit = {
    if (data.isEmpty) throw new Tensor.DimensionEmptyException
  }

  def squash(): Tensor[T] = {
    new Tensor(data, shape.squash)
  }

  // scalastyle:off magic.number
  def to2D(): Array[Array[T]] = {
    if (shape.length != 2) {
      throw new Exception("only works for tensors with 2 dimensions")
    }
    val result = Array.fill(shape(0), shape(1))(data(0))
    for (i <- 0 until shape(0)) {
      val ix = i * shape(1)
      for (j <- 0 until shape(1)) {
        val jx = ix + j
        result(i)(j) = data(jx)
      }
    }
    result
  }

  def to3D(): Array[Array[Array[T]]] = {
    if (shape.length != 3) {
      throw new Exception("only works for tensors with 3 dimensions")
    }
    val result = Array.fill(shape(0), shape(1), shape(2))(data(0))
    for (i <- 0 until shape(0)) {
      val ix = i * shape(1)
      for (j <- 0 until shape(1)) {
        val jx = (ix + j) * shape(2)
        for (k <- 0 until shape(2)) {
          val kx = jx + k
          result(i)(j)(k) = data(kx)
        }
      }
    }
    result
  }

  def to4D(): Array[Array[Array[Array[T]]]] = {
    if (shape.length != 4) {
      throw new Exception("only works for tensors with 4 dimensions")
    }
    val result = Array.fill(shape(0), shape(1), shape(2), shape(3))(data(0))
    for (i <- 0 until shape(0)) {
      val ix = i * shape(1)
      for (j <- 0 until shape(1)) {
        val jx = (ix + j) * shape(2)
        for (k <- 0 until shape(2)) {
          val kx = (jx + k) * shape(3)
          for (l <- 0 until shape(3)) {
            val lx = kx + l
            result(i)(j)(k)(l) = data(lx)
          }
        }
      }
    }
    result
  }
  // scalastyle:on magic.number

  override def equals(obj: Any): Boolean = {
    obj match {
      case t: Tensor[T] => {
        if (shape != t.shape) {
          false
        } else if (data.length != t.data.length) {
          false
        } else {
          data.zip(t.data).map { case (a, b) => a == b }.reduceLeft(_ && _)
        }
      }
      case _ => false
    }
  }

  override def hashCode: Int = {
    var h = shape.hashCode
    for (x <- data) {
      h = h ^ x.hashCode
    }
    h
  }

  override def toString: String =
    "Tensor" + "\n" + shape.toString + "\n" + data.mkString(" ")

  def equalsP(t: Tensor[T], tolerance: T): Boolean = {
    val n = implicitly[Numeric[T]]
    if (shape != t.shape) {
      false
    } else if (data.length != t.data.length) {
      false
    } else {
      data
        .zip(t.data)
        .map { case (a, b) => n.lteq(n.abs(a - b), n.abs(tolerance)) }
        .reduceLeft(_ && _)
    }
  }

  def +(other: Tensor[T]): Tensor[T] = {
    Tensor.add(this, other)
  }

  // This is matrix multiplication i.e. the inner product. Element-wise
  // multiplication can be achieved with `:*`
  def *(other: Tensor[T]): Tensor[T] = {
    Tensor.matMul(this, other)
  }

  def :*(other: Tensor[T]): Tensor[T] = {
    Tensor.mul(this, other)
  }

  def broadcast(axis: Int, times: Int): Tensor[T] = {
    if (axis < 0 || axis >= shape.length) {
      throw new Exception(s"Axis $axis is out of bounds for shape $shape")
    }
    val blockSize =
      shape.zipWithIndex
        .filter({ case (_, i) => i >= axis })
        .map(_._1)
        .product
    val numBlocks = shape.arraySize / blockSize
    val newSize   = shape.arraySize * times
    val result    = Array.fill(newSize)(implicitly[Numeric[T]].zero)
    for (i <- 0 until numBlocks) {
      for (t <- 0 until times) {
        for (j <- 0 until blockSize) {
          val originalIndex = i * blockSize + j
          val newIndex      = i * times * blockSize + t * blockSize + j
          result(newIndex) = data(originalIndex)
        }
      }
    }
    val newShape = shape.set(axis, shape(axis) * times)
    new Tensor(result, newShape)
  }
}

object Tensor {
  def apply[T : Numeric : ClassTag](matrix: Array[Array[T]]): Tensor[T] = {
    for (e <- matrix) if (e.length != matrix.head.length) {
      throw new DimensionSizeInconsistentException
    }
    val shape = Shape(Array(matrix.length, matrix.head.length))
    new Tensor(matrix.flatten, shape)
  }

  def apply[T : Numeric : ClassTag, S <: Data with Num[S]](
      data: Array[S],
      shape: Shape
  ): Tensor[T] = {
    new Tensor(data.map(Tensor.chiselTypeAsLit(_)), shape)
  }

  def fill[T : Numeric : ClassTag](shape: Shape)(element: T): Tensor[T] = {
    new Tensor(Array.fill(shape.arraySize)(element), shape)
  }

  def ofChiselType[S <: Data with Num[S], T : Numeric : ClassTag](
      gen: S,
      shape: Shape
  ): Tensor[T] = {
    (gen match {
      case _: FixedPoint => Tensor.fill[BigDecimal](shape)(BigDecimal(0))
      case _             => Tensor.fill[BigInt](shape)(BigInt(0))
    }).asInstanceOf[Tensor[T]]
  }

  def chiselTypeAsLit[S <: Data with Num[S], T : Numeric : ClassTag](
      literal: S
  ): T = {
    (literal match {
      case fp: FixedPoint => fp.litToBigDecimal
      case other          => other.litValue()
    }).asInstanceOf[T]
  }

  // scalastyle:off cyclomatic.complexity
  def litAsChiselType[S <: Data with Num[S], T : Numeric : ClassTag](gen: S)(
      value: T
  ): S = {
    (gen match {
      case fp: FixedPoint => {
        val w  = fp.getWidth.W
        val bp = fp.binaryPoint
        value match {
          case bd: BigDecimal => bd.F(w, bp)
          case f: Float       => f.toDouble.F(w, bp)
          case d: Double      => d.F(w, bp)
          case _ =>
            throw new Exception(
              s"$value (${value.getClass}) is of the wrong type to be converted to $gen"
            )
        }
      }
      case sint: SInt => {
        val w = sint.getWidth.W
        value match {
          case bi: BigInt => bi.S(w)
          case i: Int     => i.S(w)
          case l: Long    => l.S(w)
          case s: Short   => s.toInt.S(w)
          case _ =>
            throw new Exception(
              s"$value (${value.getClass}) is of the wrong type to be converted to $gen"
            )
        }
      }
      case uint: UInt => {
        val w = uint.getWidth.W
        value match {
          case bi: BigInt => bi.U(w)
          case i: Int     => i.U(w)
          case l: Long    => l.U(w)
          case s: Short   => s.toInt.U(w)
          case _ =>
            throw new Exception(
              s"$value (${value.getClass}) is of the wrong type to be converted to $gen"
            )
        }
      }
      case _ =>
        throw new Exception(s"$gen is not a valid numerical Chisel type")
    }).asInstanceOf[S]
  }
  // scalastyle:on cyclomatic.complexity

  // size is the size of the datatype in units of 8-bit bytes
  class Datatype[T : Numeric : ClassTag](val size: Int, scalaType: T)

  object Datatype extends Enumeration {
    val Short  = new Datatype(2, 0.toShort)
    val Int    = new Datatype(4, 0.toInt)
    val Long   = new Datatype(8, 0.toLong)
    val Float  = new Datatype(4, 0.toFloat)
    val Double = new Datatype(8, 0.toDouble)
  }

  def fromInputStream[T : Numeric : ClassTag](
      datatype: Datatype[T],
      shape: Shape,
      stream: InputStream
  ): Tensor[T] = {
    val buf  = new Array[Byte](datatype.size)
    val bb   = ByteBuffer.wrap(buf)
    val data = new Array[T](shape.arraySize)

    var counter = 0
    while (
      counter < shape.arraySize && stream.read(buf, 0, datatype.size) != -1
    ) {
      datatype match {
        case Datatype.Short  => data(counter) = bb.getShort.asInstanceOf[T]
        case Datatype.Int    => data(counter) = bb.getInt.asInstanceOf[T]
        case Datatype.Long   => data(counter) = bb.getLong.asInstanceOf[T]
        case Datatype.Float  => data(counter) = bb.getFloat.asInstanceOf[T]
        case Datatype.Double => data(counter) = bb.getDouble.asInstanceOf[T]
      }
      bb.clear()
      counter += 1
    }
    new Tensor(data, shape)
  }

  def offset(shape: Shape, index: Array[Int]): Int = {
    var offset = index(index.length - 1)
    for (i <- 0 until shape.length - 1) {
      offset = offset + index(i) * shape.slice(i + 1, shape.length).product
    }
    offset
  }

  def flatten[T : ClassTag](arr: Array[Array[Array[T]]]): Array[T] =
    arr.map(_.flatten).flatten

  def flatten[T : ClassTag](arr: Array[Array[Array[Array[T]]]]): Array[T] =
    arr.map(_.map(_.flatten).flatten).flatten

  // exceptions
  class DimensionEmptyException
      extends Exception(
        "Matrix dimension size must not be 0"
      )
  class DimensionSizeInconsistentException
      extends Exception(
        "Matrix dimension must have consistent size across all elements"
      )
  class DimensionSizeMismatchException
      extends Exception(
        "Inner dimensions of matrix product must be equal"
      )

  class ShapeDataMismatchException(shape: Shape, dataLength: Int)
      extends Exception(
        s"shape $shape (${shape.arraySize} elements) doesn't match data size " +
          s"$dataLength"
      )

  class ShapeMismatchException(left: Shape, right: Shape)
      extends Exception(s"Shapes do not match: $left is not equal to $right")

  // linear algebra operations

  def matMul[T : Numeric : ClassTag](
      a: Tensor[T],
      b: Tensor[T]
  ): Tensor[T] = {
    val r     = goldenMatMatMul(a.to2D(), b.to2D())
    val shape = Shape(r.length, r.head.length)
    new Tensor(r.flatten, shape)
  }

  def add[T : Numeric : ClassTag](
      left: Tensor[T],
      right: Tensor[T]
  ): Tensor[T] = {
    pointwise((l, r) => l + r, left, right)
  }

  def mul[T : Numeric : ClassTag](
      left: Tensor[T],
      right: Tensor[T]
  ): Tensor[T] = {
    pointwise((l, r) => l * r, left, right)
  }

  def pointwise[T : Numeric : ClassTag](
      op: (T, T) => T,
      left: Tensor[T],
      right: Tensor[T]
  ): Tensor[T] = {
    if (left.shape != right.shape) {
      throw new ShapeMismatchException(left.shape, right.shape)
    }
    new Tensor(
      left.data.zip(right.data).map({ case (l, r) => op(l, r) }),
      left.shape,
    )
  }

  // golden models
  // TODO rename them and convert them to work on Tensors instead
  def goldenMatVecMul[T : Numeric : ClassTag](
      mat: Array[Array[T]],
      vec: Array[T]
  ): Array[T] = {
    if (mat.length == 0) return Array.empty[T]
    if (mat.head.length == 0) return Array.empty[T]
    val result = Array.fill(mat.length)(implicitly[Numeric[T]].zero)
    for (i <- mat.indices) {
      for (j <- mat.head.indices) {
        result(i) += mat(i)(j) * vec(j)
      }
    }
    result
  }

  def goldenMatMatMul[T : Numeric : ClassTag](
      a: Array[Array[T]],
      b: Array[Array[T]]
  ): Array[Array[T]] = {
    if (a.length == 0) return Array.empty[Array[T]]
    if (a.head.length != b.length)
      throw new Tensor.DimensionSizeMismatchException
    if (a.head.length == 0) return Array.empty[Array[T]]
    if (b.head.length == 0) return Array.empty[Array[T]]
    val result =
      Array.fill(a.length, b.head.length)(implicitly[Numeric[T]].zero)
    for (i <- a.indices) {
      for (j <- a.head.indices) {
        for (k <- b.head.indices) {
          result(i)(k) += a(i)(j) * b(j)(k)
        }
      }
    }
    result
  }

  // scalastyle:off magic.number
  def goldenConv[T : Numeric : ClassTag](
      image: Tensor[T],
      filter: Tensor[T],
      paddingElement: T
  ): Tensor[T] = {
    // image is assumed to be in form HxWxI
    // filter is assumed to be in form HxWxIxO
    // padding is assumed to be 0 to produce an output the same size as the input image
    val fh      = filter.shape(0)
    val fw      = filter.shape(1)
    val co      = filter.shape(3)
    val h       = image.shape(0)
    val w       = image.shape(1)
    val padding = paddingForSame((fh, fw))
    val img = image
      .pad(paddingElement, Array(padding(0), padding(1), (0, 0)))
      .transpose(Array(2, 0, 1))
    val flt    = filter.transpose(Array(3, 2, 0, 1))
    val result = Conv.conv2d(img.to3D, flt.to4D)
    val output = new Tensor(result.map(_.flatten).flatten, Shape(co, h, w))
    output.transpose(Array(1, 2, 0))
  }
  // scalastyle:on magic.number

  def goldenMaxPool[T : Numeric : ClassTag](
      image: Tensor[T],
      poolH: Int,
      poolW: Int,
      stride: Int
  ): Tensor[T] = {
    // Shape(224, 224, 64)
    // Shape(h, w, ch) === image.shape
    val imageH  = image.shape(0)
    val imageW  = image.shape(1)
    val imageCh = image.shape(2)

    val poolShape = Shape(imageH / stride, imageW / stride, imageCh)
    val pool = new Tensor(
      Array.fill(poolShape.arraySize)(implicitly[Numeric[T]].zero),
      poolShape
    )

    // image is assumed to be in form HxWxC
    for {
      h <- 0 until imageH - (poolH - 1) by stride
      w <- 0 until imageW - (poolW - 1) by stride
      c <- 0 until imageCh
    } {

      val index = Index(
        Right(Slice(h, math.min(h + poolH, imageH))),
        Right(Slice(w, math.min(w + poolW, imageW))),
        Left(c)
      )

      val max = image(index).max

      pool.set(Array((h / stride), (w / stride), c), max)
    }
    pool
  }

  def resultTensorForConvolution(
      imageShape: Shape,
      filterShape: Shape,
      padding: Array[(Int, Int)]
  ): Tensor[BigInt] = {
    if (padding.length != 2) {
      throw new Exception("2 dimensional tensors only")
    }
    val (h, w)   = (imageShape(0), imageShape(1))
    val (fh, fw) = (filterShape(0), filterShape(1))
    val (t, b)   = padding(0)
    val (l, r)   = padding(1)
    val n        = h + t + b - (fh - 1)
    val m        = w + l + r - (fw - 1)
    new Tensor(
      Array.fill(n * m * filterShape(3))(BigInt(0)),
      Shape(n, m, filterShape(3))
    )
  }

  def paddingForSame(filterShape: (Int, Int)): Array[(Int, Int)] = {
    val (h, w)          = filterShape
    val paddingVertical = h - 1
    val paddingTop      = paddingVertical / 2
    val paddingBottom   = paddingVertical - paddingTop

    val paddingHorizontal = w - 1
    val paddingLeft       = paddingHorizontal / 2
    val paddingRight      = paddingHorizontal - paddingLeft

    Array((paddingTop, paddingBottom), (paddingLeft, paddingRight))
  }

}
