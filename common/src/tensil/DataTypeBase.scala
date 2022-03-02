/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

abstract class DataTypeBase[T] {
  def sizeBytes: Int

  def fromBytes(bytes: Array[Byte]): T
  def toBytes(x: T): Array[Byte]

  def fromFloat(f: Float): T
  def fromDouble(d: Double): T

  implicit def numeric: Numeric[T]

  def resetOverUnderflowStats(): Unit
  def reportOverUnderflowStats(): Unit
}
