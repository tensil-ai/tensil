/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mutex

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.experimental.BundleLiterals._
import chisel3.util.Decoupled
import tensil.util.decoupled.Counter
import chisel3.util.Queue
import chisel3.util.DecoupledIO
import tensil.util.zero
import chisel3.util.log2Ceil

class Lock(val numActors: Int) extends Bundle {
  val held = Bool()
  val by   = UInt(log2Ceil(numActors).W)
}

object Lock {
  def apply(numActors: Int, held: Bool, by: UInt): Lock = {
    (new Lock(numActors)).Lit(
      _.held -> held,
      _.by   -> by,
    )
  }

  def apply(numActors: Int): Lock = apply(numActors, false.B, 0.U)
}

class LockControl(
    val numActors: Int,
    val numLocks: Int,
) extends Bundle {
  val lock    = UInt(log2Ceil(numLocks).W)
  val acquire = Bool() // false = release, true = acquire
  val by      = UInt(log2Ceil(numActors).W)
}
