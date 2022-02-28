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
  val dram0ToMemory = 0x0.U
  val memoryToDram0 = 0x1.U
  val dram1ToMemory = 0x2.U
  val memoryToDram1 = 0x3.U
  // 0x4 - 0x9 are unused
  val _memoryToArrayToAcc           = 0xa.U // reserved internal only dataflow kind
  val _arrayToAcc                   = 0xb.U // reserved internal only dataflow kind
  val accumulatorToMemory           = 0xc.U
  val memoryToAccumulator           = 0xd.U
  val _unused                       = 0xe.U
  val memoryToAccumulatorAccumulate = 0xf.U

  val all = Array(
    dram0ToMemory,
    memoryToDram0,
    dram1ToMemory,
    memoryToDram1,
    accumulatorToMemory,
    memoryToAccumulator,
    memoryToAccumulatorAccumulate
  )

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

  def isValid(kind: UInt): Bool = {
    kind <= memoryToDram1 ||
    kind === accumulatorToMemory ||
    kind === memoryToAccumulator ||
    kind === memoryToAccumulatorAccumulate
  }
}
