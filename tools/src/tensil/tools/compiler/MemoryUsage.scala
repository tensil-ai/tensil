/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

object MemoryUsage {
  def apply(usages: Seq[MemoryUsage]): MemoryUsage =
    if (!usages.isEmpty)
      new MemoryUsage(
        maxSize = usages.map(_.maxSize).max,
        aggSize = usages.map(_.aggSize).sum
      )
    else MemoryUsage(0, 0)
}

case class MemoryUsage(
    maxSize: MemoryAddressRaw,
    aggSize: MemoryAddressRaw
)
