package tensil.tcu

import chisel3._
import chisel3.util.log2Ceil
import tensil.mem.Size

class SystolicArrayControl extends Bundle {
  val load   = Bool()
  val zeroes = Bool()
}

class SystolicArrayControlWithSize(val depth: Long)
    extends SystolicArrayControl
    with Size {
  val size = UInt(log2Ceil(depth).W)
}
