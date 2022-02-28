package tensil.data

import java.io.OutputStream
import java.nio.ByteBuffer

class TensorWriter(stream: OutputStream, data: Array[Array[Float]]) {
  val bytes = new Array[Byte](4)
  val bb    = ByteBuffer.wrap(bytes)

  def write(): Unit = {
    for (row <- data) {
      for (value <- row) {
        bb.putFloat(value)
        stream.write(bytes)
        bb.clear()
      }
    }
  }
}
