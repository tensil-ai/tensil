package tensil.mem

import chisel3._
import chisel3.util.log2Ceil

class MemRequest(val depth: Long) extends Bundle with Address {
  val write   = Bool()
  val address = UInt(log2Ceil(depth).W)
}
