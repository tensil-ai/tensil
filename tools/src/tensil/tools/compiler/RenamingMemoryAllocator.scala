/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import scala.collection.mutable
import tensil.tools.CompilerException

object RenamingMemoryAllocator {
  def apply(space: MemorySpace) =
    new RenamingMemoryAllocator(
      space,
      mutable.Map.empty[MemoryAddress, MemoryAddress]
    )
}

class RenamingMemoryAllocator private (
    space: MemorySpace,
    private val renameMap: mutable.Map[MemoryAddress, MemoryAddress]
) extends mutable.Cloneable[RenamingMemoryAllocator] {
  def allocate(refAddress: MemoryAddress): MemoryAddress =
    renameMap.get(refAddress) match {
      case Some(allocatedAddress) => allocatedAddress
      case None =>
        space.allocate(refAddress.ref, 1) match {
          case Some(allocatedSpan) =>
            val allocatedAddress = allocatedSpan(0)
            renameMap(refAddress) = allocatedAddress
            allocatedAddress

          case None =>
            throw new CompilerException(
              s"Insufficient ${space.name} memory to allocate ${refAddress}"
            )
        }

    }

  def free(): Unit = {
    space.free(renameMap.values.toArray)
    renameMap.clear()
  }

  override def clone(): RenamingMemoryAllocator =
    new RenamingMemoryAllocator(space.clone(), renameMap.clone())
}
