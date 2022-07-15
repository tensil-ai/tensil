/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

object MemoryAddressRaw {
  val Zero = 0L
}

object MemoryRef {
  val Invalid: MemoryRef = Short.MaxValue
}

object MemoryTag {
  val Invalid: MemoryTag = Byte.MaxValue
  val Zeroes: MemoryTag  = Byte.MaxValue - 1

  val Temp: MemoryTag         = 0
  val Accumulators: MemoryTag = 1
  val Local: MemoryTag        = 2
  val DRAM0: MemoryTag        = 3
  val DRAM1: MemoryTag        = 4

  def toString(tag: MemoryTag) =
    tag match {
      case MemoryTag.Invalid      => "Invalid"
      case MemoryTag.Zeroes       => "Zeroes"
      case MemoryTag.Temp         => "Temp"
      case MemoryTag.Accumulators => "Acc"
      case MemoryTag.Local        => "Local"
      case MemoryTag.DRAM0        => "DRAM0"
      case MemoryTag.DRAM1        => "DRAM1"
    }
}

object MemoryAddress {
  val width    = 64
  val tagWidth = 8
  val refWidth = 16
  val tagShift = width - tagWidth
  val refShift = width - tagWidth - refWidth

  val tagMask = (1L << tagWidth) - 1
  val refMask = (1L << refWidth) - 1
  val rawMask = (1L << refShift) - 1

  val Invalid = MemoryAddress(MemoryTag.Invalid, MemoryRef.Invalid, 0)
  val Zeroes  = MemoryAddress(MemoryTag.Zeroes, MemoryRef.Invalid, 0)

  def mkSpan(
      tag: MemoryTag,
      ref: MemoryRef,
      base: MemoryAddressRaw,
      size: MemoryAddressRaw
  ): MemorySpan =
    (base until base + size)
      .map(v => MemoryAddress(tag, ref, v))
      .toArray

  def apply(
      tag: MemoryTag,
      ref: MemoryRef,
      raw: MemoryAddressRaw
  ): MemoryAddress =
    ((tag.toLong & tagMask) << tagShift) | ((ref.toLong & refMask) << refShift) | (raw.toLong & rawMask)
}
