/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import tensil.tools.CompilerException

object HeapMemorySpace {
  def apply(
      name: String,
      tag: MemoryTag,
      depth: MemoryAddressRaw
  ) =
    new HeapMemorySpace(
      name,
      tag,
      depth
    )
}

class HeapMemorySpace private (
    val name: String,
    val tag: MemoryTag,
    val depth: MemoryAddressRaw,
) extends MemorySpace {
  private var aggSizeVar  = MemoryAddressRaw.Zero
  private var maxSizeVar  = MemoryAddressRaw.Zero
  private var rawFreeSpan = (MemoryAddressRaw.Zero until depth).toArray

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

  override def fork(): MemorySpace =
    throw new CompilerException("Forking heap memory space is not supported")

  override def usage = MemoryUsage(maxSize = maxSizeVar, aggSize = aggSizeVar)
}
