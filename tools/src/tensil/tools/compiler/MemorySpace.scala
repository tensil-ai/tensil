/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import scala.collection.mutable

case class MemoryUsage(
    maxSize: MemoryAddressRaw,
    aggSize: MemoryAddressRaw
)

trait MemorySpace {
  val name: String

  def allocate(
      ref: MemoryRef,
      size: MemoryAddressRaw
  ): Option[MemorySpan]

  def free(span: MemorySpan): Unit

  def fork(): MemorySpace

  def usage: MemoryUsage
}
