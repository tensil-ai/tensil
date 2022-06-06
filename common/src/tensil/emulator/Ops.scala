package tensil.emulator

import scala.reflect.ClassTag
import tensil.NumericWithMAC

object Ops {
  def matMul[T : NumericWithMAC : ClassTag](
      a: Array[Array[T]],
      b: Array[Array[T]]
  ): Array[Array[T]] = {
    if (a.length == 0) return Array.empty[Array[T]]
    if (a.head.length != b.length)
      throw new Exception("Dimension size mismatch")
    if (a.head.length == 0) return Array.empty[Array[T]]
    if (b.head.length == 0) return Array.empty[Array[T]]
    val result =
      Array.fill(a.length, b.head.length)(implicitly[NumericWithMAC[T]].zero)
    for (i <- a.indices) {
      for (j <- a.head.indices) {
        for (k <- b.head.indices) {
          result(i)(k) =
            implicitly[NumericWithMAC[T]].mac(a(i)(j), b(j)(k), result(i)(k))
        }
      }
    }
    result
  }
}
