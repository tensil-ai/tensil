/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

import chisel3._
import chisel3.util.log2Ceil

class InnerPort[T <: Data](gen: T, depth: Long) extends Bundle {
  val address = Input(UInt(log2Ceil(depth).W))
  val read = new Bundle {
    val enable = Input(Bool())
    val data   = Output(gen)
  }
  val write = new Bundle {
    val enable = Input(Bool())
    val data   = Input(gen)
  }
}
