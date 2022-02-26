package tf2rtl

import upickle.default.{ReadWriter, macroRW}
import upickle.implicits.key

import java.io.{DataOutputStream, DataInputStream}

abstract class ArchitectureDataType(val name: String) {
  def sizeBytes: Int
  def error: Float

  def reportAndResetOverUnderflowStats(): Unit

  def writeFloatConst(f: Float, stream: DataOutputStream): Unit
  def readFloatConst(stream: DataInputStream): Float

  override def toString() = name
}

class ArchitectureDataTypeWithBase[T](
    name: String,
    val base: DataTypeBase[T],
    val error: Float
) extends ArchitectureDataType(name) {
  def sizeBytes: Int = base.sizeBytes

  def reportAndResetOverUnderflowStats(): Unit = {
    base.reportOverUnderflowStats()
    base.resetOverUnderflowStats()
  }

  def writeFloatConst(f: Float, stream: DataOutputStream): Unit =
    writeConst(base.fromFloat(f), stream)
  def readFloatConst(stream: DataInputStream): Float =
    base.numeric.toFloat(readConst(stream))

  def writeConst(v: T, stream: DataOutputStream): Unit =
    stream.write(base.toBytes(v))
  def readConst(stream: DataInputStream): T =
    base.fromBytes(stream.readNBytes(base.sizeBytes))
}

object ArchitectureDataType {
  val FP8BP4  = new ArchitectureDataTypeWithBase("FP8BP4", Fixed8bp4, 0.2f)
  val FP16BP8 = new ArchitectureDataTypeWithBase("FP16BP8", Fixed16bp8, 0.2f)
  val FP18BP10 =
    new ArchitectureDataTypeWithBase("FP18BP10", Fixed18bp10, 0.05f)
  val FP32BP16 =
    new ArchitectureDataTypeWithBase("FP32BP16", Fixed32bp16, 0.01f)
  val FLOAT32 = new ArchitectureDataTypeWithBase("FLOAT32", Float32, 0.0001f)

  implicit val rw: ReadWriter[ArchitectureDataType] = upickle.default
    .readwriter[String]
    .bimap[ArchitectureDataType](
      dt => dt.name,
      name =>
        name match {
          case FP8BP4.name   => FP8BP4
          case FP16BP8.name  => FP16BP8
          case FP18BP10.name => FP18BP10
          case FP32BP16.name => FP32BP16
          case FLOAT32.name  => FLOAT32
          case name =>
            throw new Exception(s"$name is not supported")
        }
    )
}
