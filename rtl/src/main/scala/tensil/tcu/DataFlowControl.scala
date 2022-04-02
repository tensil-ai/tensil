/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util.log2Ceil
import tensil.mem.Size

class DataFlowControl extends Bundle {
  val kind = UInt(4.W)
}
class DataFlowControlWithSize(val depth: Long)
    extends DataFlowControl
    with Size {
  val size = UInt(log2Ceil(depth).W)
}

object DataFlowControlWithSize {
  def apply(depth: Long)(kind: UInt, size: UInt): DataFlowControlWithSize = {
    if (kind.isLit() && size.isLit()) {
      new DataFlowControlWithSize(depth).Lit(_.kind -> kind, _.size -> size)
    } else {
      val w = Wire(new DataFlowControlWithSize(depth))
      w.kind := kind
      w.size := size
      w
    }
  }
}

object DataFlowControl {
  val memoryToArrayWeight = 0x1.U
  val _memoryToArrayToAcc = 0x2.U
  val _arrayToAcc         = 0x3.U
  val accumulatorToMemory = 0x4.U
  val memoryToAccumulator = 0x5.U
  val _unused             = 0x6.U
  // val memoryToAccumulatorAccumulate = 0x7.U

  // val all = Array(
  //   dram0ToMemory,
  //   memoryToDram0,
  //   dram1ToMemory,
  //   memoryToDram1,
  //   accumulatorToMemory,
  //   memoryToAccumulator,
  //   memoryToAccumulatorAccumulate
  // )

  def apply(kind: UInt): DataFlowControl = {
    if (kind.isLit()) {
      new DataFlowControl().Lit(
        _.kind -> kind,
      )
    } else {
      val w = Wire(new DataFlowControl())
      w.kind := kind
      w
    }
  }

  // def isValid(kind: UInt): Bool = {
  //   kind <= memoryToDram1 ||
  //   kind === accumulatorToMemory ||
  //   kind === memoryToAccumulator ||
  //   kind === memoryToAccumulatorAccumulate
  // }
}
