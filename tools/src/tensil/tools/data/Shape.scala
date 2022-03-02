/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.data

class Shape(private val dimensions: Array[Int]) extends Iterable[Int] {
  val length = dimensions.length

  def apply(i: Int): Int = dimensions(i)

  def set(axis: Int, size: Int): Shape = {
    if (axis < 0 || axis >= dimensions.length) {
      throw new Exception(s"Axis $axis is outside of shape $this")
    }
    val dims = dimensions.clone()
    dims(axis) = size
    new Shape(dims)
  }

  def arraySize: Int = dimensions.product

  def squash: Shape = new Shape(dimensions.filter(_ != 1))

  override def iterator: Iterator[Int] = dimensions.iterator

  override def equals(obj: Any): Boolean = {
    obj match {
      case s: Shape => {
        if (dimensions.length != s.dimensions.length) {
          false
        } else {
          if (dimensions.length == 0 && s.dimensions.length == 0)
            true
          else
            dimensions
              .zip(s.dimensions)
              .map { case (a, b) => a == b }
              .reduceLeft(_ && _)
        }
      }
      case _ => false
    }
  }

  override def hashCode: Int = {
    var h = dimensions.length
    for (d <- dimensions) {
      h = h ^ d
    }
    h
  }

  override def toString: String = "Shape(" + dimensions.mkString(", ") + ")"
}

object Shape {
  def apply(dimensions: Array[Int]): Shape = new Shape(dimensions)

  def apply(dimensions: Int*): Shape = new Shape(dimensions.toArray)
}
