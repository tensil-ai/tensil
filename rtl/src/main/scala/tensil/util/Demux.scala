/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util

import chisel3._
import chisel3.util.{Decoupled, Queue}

object Demux {
  def apply[T <: Data](cond: Bool, con: T, alt: T): T = {
    val input = Wire(con.cloneType)
    when(cond) {
      con := input
    }.otherwise {
      alt := input
    }
    input
  }
}
