/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

object HeapMemorySpace {
  def apply(
      name: String,
      tag: MemoryTag,
      depth: MemoryAddressRaw
  ) =
    new HeapMemorySpace(
      name,
      tag,
      depth,
      MemoryAddressRaw.Zero,
      MemoryAddressRaw.Zero,
      (MemoryAddressRaw.Zero until depth).toArray
    )
}

class HeapMemorySpace private (
    val name: String,
    val tag: MemoryTag,
    val depth: MemoryAddressRaw,
    private var aggSizeVar: MemoryAddressRaw,
    private var maxSizeVar: MemoryAddressRaw,
    private var rawFreeSpan: Array[MemoryAddressRaw]
) extends MemorySpace {

  override def allocate(
      ref: MemoryRef,
      size: MemoryAddressRaw
  ): Option[MemorySpan] = {
    if (size <= rawFreeSpan.length) {

      val (rawAllocatedSpan, nextRawFreeSpan) =
        rawFreeSpan.splitAt(size.toInt)

      rawFreeSpan = nextRawFreeSpan

      val currentSize = depth - rawFreeSpan.length
      maxSizeVar = if (currentSize > maxSizeVar) currentSize else maxSizeVar
      aggSizeVar += size

      Some(rawAllocatedSpan.map(MemoryAddress(tag, ref, _)))
    } else None
  }

  override def free(span: MemorySpan): Unit = {
    rawFreeSpan =
      (span.filter(a => a.tag == tag).map(_.raw) ++ rawFreeSpan).sorted.toArray
  }

  override def clone(): MemorySpace = {
    new HeapMemorySpace(
      name,
      tag,
      depth,
      aggSizeVar,
      maxSizeVar,
      rawFreeSpan.clone()
    )
  }

  override def maxSize: MemoryAddressRaw = maxSizeVar
  override def aggSize: MemoryAddressRaw = aggSizeVar
}
