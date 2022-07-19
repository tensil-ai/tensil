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
    private val base: MemoryAddressRaw
) extends MemorySpace {
  private var next = base
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

  override def fork(): MemorySpace =
    new ArenaMemorySpace(name, tag, depth, next)

  override def usage = MemoryUsage(maxSize = next, aggSize = next - base)
}
