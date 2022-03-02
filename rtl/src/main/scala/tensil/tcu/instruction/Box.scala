/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu.instruction

import scala.language.implicitConversions

class Box[T](value: T) {
  private var _value      = value
  def set(value: T): Unit = _value = value
  def get: T              = _value
}

object Box {
  implicit def convert[T](b: Box[T]): T = {
    b.get
  }

  def apply[T](value: T): Box[T] = new Box(value)
}
