package tensil.data

class MultiRange(range: Int*) extends Iterator[Array[Int]] {
  private val n        = range.length
  private val counters = Array.fill(n)(0)
  private var last     = false

  def next: Array[Int] = {
    val result = counters.clone()
    for (i <- n - 1 to 0 by -1) {
      if (i == n - 1) {
        counters(i) += 1
      } else if (counters(i + 1) == range(i + 1)) {
        counters(i) += 1
      }
    }
    for (i <- counters.indices) {
      if (counters(i) == range(i)) {
        counters(i) = 0
      }
    }
    result
  }

  def hasNext: Boolean = {
    val pLast = last
    last =
      counters.indices.map(i => counters(i) == range(i) - 1).reduceLeft(_ && _)
    !pLast
  }
}
