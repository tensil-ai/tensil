package tensil.data

import java.io.InputStream
import java.nio.ByteBuffer

// reads Floats from an InputStream and produces a Seq[Float] on each iteration
class TensorReader(stream: InputStream, arrayWidth: Int)
    extends Iterator[Array[Float]] {
  val dtypeSize             = 4
  val readLength            = arrayWidth * dtypeSize
  private val bytes         = new Array[Byte](readLength)
  private val bb            = ByteBuffer.wrap(bytes)
  private val out           = new Array[Float](arrayWidth)
  private var lastRead: Int = 0

  def hasNext: Boolean = {
    lastRead = stream.read(bytes, 0, readLength)
    lastRead != -1
  }

  def next: Array[Float] = {
    for (i <- 0 until arrayWidth) {
      out(i) = bb.getFloat
    }
    bb.clear
    out.clone()
  }
}
