package tensil

class Fixed18bp10(
    val bits: Int
) extends AnyVal {}

object Fixed18bp10
    extends FixedBase(
      width = 18,
      basePoint = 10,
      sizeBytes = 4,
      fromLongBits = (bits: Long) => new Fixed18bp10(bits.toInt),
      toLongBits = (fixed: Fixed18bp10) => fixed.bits.toLong
    ) {

  implicit val numeric = mkNumeric
}
