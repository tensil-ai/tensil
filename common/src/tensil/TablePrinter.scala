package tensil

import scala.collection.mutable

object TableLine {
  def apply(values: Any*): TableLine = new TableLine(values)
  def apply(prefixValue: Any, line: TableLine): TableLine =
    new TableLine(prefixValue +: line.values)
}

class TableLine(it: Iterable[Any]) {
  val formatter = java.text.NumberFormat.getIntegerInstance
  val values    = it.map(toStringIndexedSeq(_)).toIndexedSeq

  private def toStringIndexedSeq(value: Any): IndexedSeq[String] =
    value match {
      case it: Iterable[Any] => it.map(toString(_)).toIndexedSeq
      case v                 => IndexedSeq(toString(v))
    }

  private def toString(value: Any): String =
    value match {
      case f: Float  => f"$f%.3f"
      case d: Double => f"$d%.3f"
      case i: Int    => formatter.format(i)
      case l: Long   => formatter.format(l)
      case v         => v.toString()
    }
}

class TablePrinter(
    val title: Option[String] = None,
    val withHeader: Boolean = false
) {
  val lines = mutable.ListBuffer.empty[TableLine]

  def addLine(line: TableLine): Unit = lines += line
  def addNamedLine(name: String, values: Any*): Unit =
    addLine(new TableLine((name + ":") +: values))

  override def toString(): String = {
    val sb = new StringBuffer()

    if (!lines.isEmpty) {
      def length(value: Any) =
        value match {
          case parts: Iterable[Any] =>
            if (parts.isEmpty) 0 else parts.map(_.toString.length).max
          case value: Any =>
            value.toString.length
        }
      val maxValuesSize = lines.map(_.values.size).max
      val maxValuesLengths = lines.map(line =>
        line.values
          .slice(0, line.values.size - 1)
          .map(length(_) + 1) :+ (length(
          line.values.last
        ) + (if (line.values.size < maxValuesSize) 1 else 0))
      )

      val maxLengths =
        (0 until maxValuesSize).map(i =>
          maxValuesLengths
            .map(valueLengths =>
              if (valueLengths.isDefinedAt(i)) valueLengths(i) else 0
            )
            .max
        )

      val hr = "-" * maxLengths.sum

      def appendHr() = {
        sb.append(hr)
        sb.append("\n")
      }

      appendHr()

      if (title.isDefined) {
        sb.append(title.get)
        sb.append("\n")

        appendHr()
      }

      def paddedValue(s: String, i: Int) =
        if (i == maxValuesSize - 1)
          s
        else
          s.padTo(maxLengths(i), ' ')

      for (i <- 0 until lines.size) {
        val line = lines(i)

        for (j <- 0 until maxValuesSize)
          if (line.values.isDefinedAt(j)) {
            val parts = line.values(j)
            if (!parts.isEmpty) {
              sb.append(paddedValue(parts.head, j))

              val newLinePadding = maxLengths.take(j).sum
              for (part <- parts.tail) {
                sb.append("\n")
                sb.append(" " * newLinePadding)
                sb.append(part)
              }
            }
          }

        sb.append("\n")

        if (withHeader && i == 0)
          appendHr()
      }

      appendHr()
    }
    sb.toString()
  }
}
