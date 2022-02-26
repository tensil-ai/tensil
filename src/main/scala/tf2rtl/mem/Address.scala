package tf2rtl.mem

import chisel3.UInt

trait Address {
  val address: UInt
}
