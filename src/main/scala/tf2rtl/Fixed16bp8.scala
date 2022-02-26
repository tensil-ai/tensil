package tf2rtl

class Fixed16bp8(
    val bits: Short
) extends AnyVal {}

object Fixed16bp8
    extends FixedBase(
      width = 16,
      basePoint = 8,
      sizeBytes = 2,
      fromLongBits = (bits: Long) => new Fixed16bp8(bits.toShort),
      toLongBits = (fixed: Fixed16bp8) => fixed.bits.toLong
    ) {

  implicit val numeric = mkNumeric
}
