/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import tensil.tools.CompilerException

object ArenaMemorySpace {
  def apply(
      name: String,
      tag: MemoryTag,
      depth: MemoryAddressRaw
  ) =
    new ArenaMemorySpace(
      name,
      tag,
      depth,
      MemoryAddressRaw.Zero
    )
}

class ArenaMemorySpace private (
    val name: String,
    val tag: MemoryTag,
    val depth: MemoryAddressRaw,
    private var next: MemoryAddressRaw
) extends MemorySpace {
  def allocate(
      ref: MemoryRef,
      size: MemoryAddressRaw
  ): Option[MemorySpan] =
    if (next + size <= depth) {
      val span = MemoryAddress.mkSpan(
        tag,
        ref,
        next,
        size
      )

      next += size

      Some(span)
    } else None

  def free(span: MemorySpan): Unit =
    throw new CompilerException(
      "Freeing from arena memory space is not supported"
    )

  override def clone(): MemorySpace =
    new ArenaMemorySpace(name, tag, depth, next)

  override def maxSize: MemoryAddressRaw = next
  override def aggSize: MemoryAddressRaw = next
}
