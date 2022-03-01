package tensil

object Fixed32bp16
    extends FixedBase(
      width = 32,
      basePoint = 16,
      sizeBytes = 4,
      fromLongBits = (bits: Long) => new Fixed32bp16(bits.toInt),
      toLongBits = (fixed: Fixed32bp16) => fixed.bits.toLong
    ) {

  implicit val numeric = mkNumeric
}

class Fixed32bp16(
    val bits: Int
) extends AnyVal {}
