package tf2rtl.tools.compiler

import scala.collection.mutable
import tf2rtl.Architecture

/*
 * Single- and double-address rollups are used
 * to determine if multiple instruction can be
 * "rolled up" into a single instruction in case
 * of addresses forming blocks of memory with
 * constant stride.
 */

abstract class Rollup {
  def mkStrides(depth: Int) = (0 until depth).map(1L << _)
}

class SingleAddressReverseRollup(
    cb: (Int, MemoryAddress, MemoryAddressRaw) => Unit,
    arch: Architecture
) extends Rollup {
  private var previousAddress: Option[MemoryAddress] = None
  private var stride: Option[Int]                    = None
  private var totalSize: MemoryAddressRaw            = MemoryAddressRaw.Zero
  private var allowedStrides                         = mkStrides(arch.stride0Depth)

  def callback(a: MemoryAddress, s: Int) = {
    cb(
      if (a.tag != MemoryTag.Zeroes) s else 0,
      a,
      totalSize
    )

    previousAddress = None
    stride = None
    totalSize = 0L
  }

  def emit(address: MemoryAddress): Unit = {
    def continue() = {
      previousAddress = Some(address)
      totalSize = totalSize + 1L
    }

    def break(a: MemoryAddress, s: Int) = {
      callback(a, s)
      emit(address)
    }

    (previousAddress, stride) match {
      case (None, _) =>
        previousAddress = Some(address)

      case (Some(a), None) =>
        val s =
          allowedStrides.indexOf(a.raw - address.raw)

        if (
          a.tag == address.tag &&
          (a.tag == MemoryTag.Zeroes || s != -1)
        ) {
          stride = Some(s)
          continue()
        } else
          break(a, 0)

      case (Some(a), Some(s)) =>
        if (
          (a.tag == MemoryTag.Zeroes || a.raw - allowedStrides(
            s
          ) == address.raw) && a.tag == address.tag
        )
          continue()
        else
          break(a, s)
    }
  }

  def finalEmit(): Unit = {
    (previousAddress, stride) match {
      case (None, _) =>
      case (Some(a), None) =>
        callback(a, 0)
      case (Some(a), Some(s)) =>
        callback(a, s)
    }
  }
}

class DoubleAddressRollup(
    cb: (Int, MemoryAddress, Int, MemoryAddress, MemoryAddressRaw) => Unit,
    arch: Architecture
) extends Rollup {
  private var previousAddresses: Option[(MemoryAddress, MemoryAddress)] = None
  private var strides: Option[(Int, Int)]                               = None
  private var totalSize: MemoryAddressRaw                               = MemoryAddressRaw.Zero
  private var allowedStrides0                                           = mkStrides(arch.stride0Depth)
  private var allowedStrides1                                           = mkStrides(arch.stride1Depth)

  private def callback(
      a0: MemoryAddress,
      a1: MemoryAddress,
      s0: Int,
      s1: Int
  ): Unit = {
    cb(
      if (a0.tag != MemoryTag.Zeroes) s0 else 0,
      a0,
      if (a1.tag != MemoryTag.Zeroes) s1 else 0,
      a1,
      totalSize
    )

    previousAddresses = None
    strides = None
    totalSize = 0L
  }

  def emit(
      address0: MemoryAddress,
      address1: MemoryAddress
  ): Unit = {
    def continue() = {
      totalSize = totalSize + 1L
    }

    def break(
        a0: MemoryAddress,
        a1: MemoryAddress,
        s0: Int,
        s1: Int
    ) = {
      callback(a0, a1, s0, s1)
      emit(address0, address1)
    }

    (previousAddresses, strides) match {
      case (None, _) =>
        previousAddresses = Some(address0, address1)

      case (Some((a0, a1)), None) =>
        val s0 =
          allowedStrides0.indexOf(address0.raw - a0.raw)
        val s1 =
          allowedStrides1.indexOf(address1.raw - a1.raw)

        if (
          a0.tag == address0.tag &&
          a1.tag == address1.tag &&
          (a0.tag == MemoryTag.Zeroes || s0 != -1) &&
          (a1.tag == MemoryTag.Zeroes || s1 != -1)
        ) {
          strides = Some((s0, s1))
          continue()
        } else
          break(a0, a1, 0, 0)

      case (Some((a0, a1)), Some((s0, s1))) =>
        if (
          (a0.tag == MemoryTag.Zeroes || a0.raw + (totalSize + 1) * allowedStrides0(
            s0
          ) == address0.raw) &&
          a0.tag == address0.tag &&
          (a1.tag == MemoryTag.Zeroes || a1.raw + (totalSize + 1) * allowedStrides1(
            s1
          ) == address1.raw) &&
          a1.tag == address1.tag
        ) continue()
        else
          break(a0, a1, s0, s1)
    }
  }

  def finalEmit(): Unit = {
    (previousAddresses, strides) match {
      case (None, _) =>
      case (Some((a0, a1)), None) =>
        callback(a0, a1, 0, 0)
      case (Some((a0, a1)), Some((s0, s1))) =>
        callback(a0, a1, s0, s1)
    }
  }
}
