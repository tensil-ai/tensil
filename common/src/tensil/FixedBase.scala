/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

abstract class FixedBase[TFixed](
    val width: Int,
    val basePoint: Int,
    val sizeBytes: Int,
    fromLongBits: (Long) => TFixed,
    toLongBits: (TFixed) => Long
) extends DataTypeBase[TFixed] {
  private val ratio = 1 << basePoint
  private val max   = (1L << (width - 1)) - 1
  private val min   = -1L << (width - 1)

  final val MinValue = mkFixed(min)
  final val MaxValue = mkFixed(max)

  private class OverUnderflowStats() {
    private var maxDelta = 0L
    private var sumDelta = 0L
    private var count    = 0L

    def reset(): Unit = {
      maxDelta = 0L
      sumDelta = 0L
      count = 0L
    }

    def count(delta: Long): Unit =
      synchronized {
        maxDelta = Math.max(maxDelta, delta)
        sumDelta += delta
        count += 1
      }

    def report(title: String): Unit =
      if (count != 0) {
        val tb = new TablePrinter(
          Some(title)
        )
        tb.addNamedLine("Maximum delta", maxDelta)
        tb.addNamedLine("Average delta", sumDelta / count)
        tb.addNamedLine("Count", count)
        print(tb)
      }
  }

  private val overflowStat  = new OverUnderflowStats()
  private val underflowStat = new OverUnderflowStats()

  def resetOverUnderflowStats(): Unit = {
    overflowStat.reset()
    underflowStat.reset()
  }

  def reportOverUnderflowStats(): Unit = {
    overflowStat.report("FIXED POINT OVERFLOW SUMMARY")
    underflowStat.report("FIXED POINT UNDERFLOW SUMMARY")
  }

  def mkNumericWithMAC =
    new NumericWithMAC[TFixed] {
      override def plus(x: TFixed, y: TFixed): TFixed =
        mkFixed(doMAC(toLongBits(x), 1L << basePoint, toLongBits(y)))
      override def minus(x: TFixed, y: TFixed): TFixed =
        mkFixed(doMAC(toLongBits(x), 1L << basePoint, -toLongBits(y)))

      override def times(x: TFixed, y: TFixed): TFixed =
        mkFixed(doMAC(toLongBits(x), toLongBits(y), 0L))

      override def mac(x: TFixed, y: TFixed, z: TFixed): TFixed =
        mkFixed(doMAC(toLongBits(x), toLongBits(y), toLongBits(z)))

      private def doMAC(x: Long, y: Long, z: Long): Long = {
        val mac = (x * y) + (z << basePoint)

        /*
         * TDOD: Multiple rounding policies can be employed. Currently
         * `round-to-nearest-even` gives best results. We should test
         * with more models to see if this needs to be parametrized.
         *
         * See: https://docs.google.com/spreadsheets/d/14U3z-yJsnC1whWWuBmbnL9ZggzcdP-VvrKsp4Y4ztPg/edit#gid=0
         */

        /*
        // round-down
        val adj = 0
         */

        /*
        // round-to-nearest-up
        val adj = if ((mac & (1L << (BasePoint - 1))) != 0) 1 else 0
         */

        // round-to-nearest-even
        val adj = if (
          (mac & (1L << (basePoint - 1))) != 0 && ((mac & ((1L << (basePoint - 1)) - 1)) != 0 || (mac & (1L << (basePoint))) != 0)
        ) 1
        else 0

        /*
        // round-to-odd
        val adj = if (
          (mac & (1L << (BasePoint))) == 0 && (mac & ((1L << (BasePoint)) - 1)) != 0
        ) 1
        else 0
         */

        (mac >> basePoint) + adj
      }
      override def negate(x: TFixed): TFixed =
        mkFixed(-toLongBits(x))

      override def fromInt(x: Int): TFixed =
        mkFixed(x.toLong << basePoint)

      override def toLong(x: TFixed): Long =
        toLongBits(x) >> basePoint
      override def toInt(x: TFixed): Int =
        toLong(x).toInt
      override def toFloat(x: TFixed): Float =
        toLongBits(x).toFloat / ratio
      override def toDouble(x: TFixed): Double =
        toLongBits(x).toDouble / ratio

      override def compare(x: TFixed, y: TFixed): Int = {
        val xBits = toLongBits(x)
        val yBits = toLongBits(y)

        if (xBits == yBits) 0
        else if (xBits > yBits) 1
        else -1
      }
    }

  def fromFloat(f: Float): TFixed =
    mkFixed(Math.round(f * ratio).toLong)
  def fromDouble(d: Double): TFixed =
    mkFixed(Math.round(d * ratio))

  def mkFixed(l: Long): TFixed = {
    if (l > max) {
      overflowStat.count((l - max) >> basePoint)
      fromLongBits(max)
    } else if (l < min) {
      underflowStat.count((min - l) >> basePoint)
      fromLongBits(min)
    } else
      fromLongBits(l)
  }

  def fromBytes(bytes: Array[Byte]): TFixed = {
    var bits = 0L
    for (i <- 0 until sizeBytes) {
      val j = i * 8
      val k = width - j

      if (k > 0) {
        var byte = bytes(i).toLong & 0xff

        if (k <= 8 && (byte & (1 << (k - 1))) != 0)
          byte -= (1 << k)

        bits |= (byte << j)
      }
    }

    mkFixed(bits)
  }

  def toBytes(x: TFixed): Array[Byte] = {
    val arr  = Array.fill[Byte](sizeBytes)(0)
    val bits = toLongBits(x)
    for (i <- 0 until sizeBytes) {
      val j = i * 8
      val k = width - j

      if (k > 0) {
        val byte = bits >> j
        val mask = (1L << k) - 1

        arr(i) = (byte & mask).toByte
      } else
        arr(i) = 0
    }

    arr
  }
}
