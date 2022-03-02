/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.data

import scala.reflect.ClassTag

class CartesianProduct[T : ClassTag](arrays: Array[T]*)
    extends Iterator[Array[T]] {
  private var mr = new MultiRange(arrays.map(_.length): _*)

  def hasNext: Boolean = mr.hasNext

  def next: Array[T] = {
    val idx = mr.next()
    arrays.zip(idx).map({ case (a, i) => a(i) }).toArray
  }
}
