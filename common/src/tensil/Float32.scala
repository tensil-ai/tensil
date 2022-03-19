/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

import scala.Numeric.FloatAsIfIntegral
import java.io.{
  ByteArrayOutputStream,
  DataOutputStream,
  ByteArrayInputStream,
  DataInputStream
}

trait FloatAsIfIntegralWithMAC
    extends FloatAsIfIntegral
    with NumericWithMAC[Float] {
  def mac(x: Float, y: Float, z: Float): Float =
    x * y + z
}

object FloatAsIfIntegralWithMAC
    extends FloatAsIfIntegralWithMAC
    with Ordering.FloatOrdering {}

object Float32 extends DataTypeBase[Float] {
  def sizeBytes: Int = 4

  def fromBytes(bytes: Array[Byte]): Float = {
    val s = new ByteArrayInputStream(bytes)
    (new DataInputStream(s)).readFloat()
  }

  def toBytes(x: Float): Array[Byte] = {
    val s = new ByteArrayOutputStream()
    (new DataOutputStream(s)).writeFloat(x)
    s.toByteArray()
  }

  def fromFloat(f: Float): Float   = f
  def fromDouble(d: Double): Float = d.toFloat

  implicit val numericWithMAC = FloatAsIfIntegralWithMAC

  def resetOverUnderflowStats(): Unit = {}
  def reportOverUnderflowStats(): Unit = {}
}
