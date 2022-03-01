package tensil

import scala.Numeric.FloatAsIfIntegral
import java.io.{
  ByteArrayOutputStream,
  DataOutputStream,
  ByteArrayInputStream,
  DataInputStream
}

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

  implicit val numeric = FloatAsIfIntegral

  def resetOverUnderflowStats(): Unit = {}
  def reportOverUnderflowStats(): Unit = {}
}
