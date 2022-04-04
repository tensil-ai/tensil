/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util.log2Ceil
import tensil.mem.Size

class LocalDataFlowControl extends Bundle {
  val kind = UInt(4.W)
}
class LocalDataFlowControlWithSize(val depth: Long)
    extends LocalDataFlowControl
    with Size {
  val size = UInt(log2Ceil(depth).W)
}

object LocalDataFlowControlWithSize {
  def apply(
      depth: Long
  )(kind: UInt, size: UInt): LocalDataFlowControlWithSize = {
    if (kind.isLit() && size.isLit()) {
      new LocalDataFlowControlWithSize(depth)
        .Lit(_.kind -> kind, _.size -> size)
    } else {
      val w = Wire(new LocalDataFlowControlWithSize(depth))
      w.kind := kind
      w.size := size
      w
    }
  }
}

object LocalDataFlowControl {
  val memoryToArrayWeight = 0x1.U
  val _memoryToArrayToAcc = 0x2.U
  val _arrayToAcc         = 0x3.U
  val accumulatorToMemory = 0x4.U
  val memoryToAccumulator = 0x5.U
  val _unused             = 0x6.U

  def apply(kind: UInt): LocalDataFlowControl = {
    if (kind.isLit()) {
      new LocalDataFlowControl().Lit(
        _.kind -> kind,
      )
    } else {
      val w = Wire(new LocalDataFlowControl())
      w.kind := kind
      w
    }
  }
}
