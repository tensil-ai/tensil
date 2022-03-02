/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.data

case class Slice(start: Option[Int], end: Option[Int], step: Option[Int]) {
  val (length, maybeIterator) = end match {
    case Some(end) => {
      start match {
        case Some(start) => {
          step match {
            case Some(step) =>
              (Some((end - start) / step), Some(Range(start, end, step)))
            case None => (Some(end - start), Some(Range(start, end, 1)))
          }
        }
        case None => (Some(end), Some(Range(0, end, 1)))
      }
    }
    case None => (None, None)
  }

  def iterator(dimensionSize: Int): Range = {
    maybeIterator match {
      case Some(it) => it
      case None     => Range(0, dimensionSize, 1)
    }
  }
}

object Slice {
  val all = Slice(None, None, None)

  def apply(start: Int, end: Int, step: Int): Slice = {
    new Slice(Some(start), Some(end), Some(step))
  }

  def apply(end: Int): Slice = {
    Slice(0, end, 1)
  }

  def apply(start: Int, end: Int): Slice = {
    Slice(start, end, 1)
  }
}
