/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util.log2Ceil
import tensil.tcu.instruction.Box
import tensil.mutex.Comparable

class MemControlWithStride(depth: Long, val strideDepth: Int)
    extends MemControl(depth)
    with Stride
    with Reverse
    with Comparable[MemControlWithStride] {
  val stride  = UInt(log2Ceil(strideDepth).W)
  val reverse = Bool()

  def ===(other: MemControlWithStride): Bool = {
    address === other.address && write === other.write && size === other.size && stride === other.stride && reverse === other.reverse
  }
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
      address.isLit && size.isLit && stride.isLit && write.isLit && reverse.isLit
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

class MemControlWithComparable(depth: Long)
    extends MemControl(depth)
    with Comparable[MemControlWithComparable] {
  def ===(other: MemControlWithComparable): Bool = {
    write === other.write && address === other.address && size === other.size
  }
}

object MemControlWithComparable {
  def apply(
      depth: Long
  )(address: UInt, write: Bool): MemControlWithComparable = {
    val w = Wire(new MemControlWithComparable(depth))
    w.address := address
    w.write := write
    w.size := 0.U
    w
  }
}

class MemControl(val depth: Long) extends Bundle with Address with Size {
  val write   = Bool()
  val address = UInt(log2Ceil(depth).W)
  val size    = UInt(log2Ceil(depth).W)
}

object MemControl {
  def apply(depth: Long)(address: UInt, write: Bool): MemControl =
    apply(depth, address, 0.U, write)

  def apply(
      depth: Long,
      address: UInt,
      size: UInt,
      write: Bool
  ): MemControl = {
    if (address.isLit && size.isLit && write.isLit) {
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
}
