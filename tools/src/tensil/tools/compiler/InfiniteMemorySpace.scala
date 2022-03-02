/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

class InfiniteMemorySpace(val tag: MemoryTag) {
  private var next: MemoryAddressRaw = MemoryAddressRaw.Zero

  def allocate(
      ref: MemoryRef,
      size: MemoryAddressRaw
  ): MemorySpan = {
    val span = MemoryAddress.mkSpan(
      tag,
      ref,
      next,
      size
    )

    next += size

    span
  }
}
