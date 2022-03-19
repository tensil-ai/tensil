/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

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

  implicit val numericWithMAC = mkNumericWithMAC
}
