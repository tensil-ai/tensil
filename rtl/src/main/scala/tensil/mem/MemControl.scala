/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util.log2Ceil
import tensil.tcu.instruction.Box

class MemControlWithStride(depth: Long, val strideDepth: Int)
    extends MemControl(depth)
    with Stride
    with Reverse {
  val stride  = UInt(log2Ceil(strideDepth).W)
  val reverse = Bool()
}

object MemControlWithStride {
  def apply(depth: Long, strideDepth: Int)(
      address: UInt,
      size: UInt,
      stride: UInt,
      reverse: Bool,
      write: Bool,
  ): MemControlWithStride = {
    if (
      address.isLit() && size.isLit() && stride.isLit() && write
        .isLit() && reverse.isLit()
    ) {
      new MemControlWithStride(depth, strideDepth).Lit(
        _.address -> address,
        _.size    -> size,
        _.stride  -> stride,
        _.reverse -> reverse,
        _.write   -> write,
      )
    } else {
      val w = Wire(new MemControlWithStride(depth, strideDepth))
      w.address := address
      w.size := size
      w.stride := stride
      w.write := write
      w.reverse := reverse
      w
    }
  }
}

class MemControl(val depth: Long) extends Bundle with Address with Size {
  val write   = Bool()
  val address = UInt(log2Ceil(depth).W)
  val size    = UInt(log2Ceil(depth).W)
}

object MemControl {
  val depth       = Box(256)
  val strideDepth = Box(128)

  def apply(depth: Long)(address: UInt, write: Bool): MemControl =
    apply(depth, address, 0.U, write)

  def apply(
      depth: Long,
      address: UInt,
      size: UInt,
      write: Bool
  ): MemControl = {
    if (address.isLit() && size.isLit() && write.isLit()) {
      new MemControl(depth).Lit(
        _.address -> address,
        _.write   -> write,
        _.size    -> size,
      )
    } else {
      val w = Wire(new MemControl(depth))
      w.address := address
      w.write := write
      w.size := size
      w
    }
  }

  def apply(address: UInt, write: Bool): MemControl = {
    apply(depth.get)(address, write)
  }
}
