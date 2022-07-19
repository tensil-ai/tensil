/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import scala.collection.mutable
import tensil.tools.CompilerException

object RenamingMemoryAllocator {
  def apply(space: MemorySpace, refTags: Set[MemoryTag]) =
    new RenamingMemoryAllocator(
      space,
      refTags,
      mutable.Map.empty[MemoryAddress, MemoryAddress]
    )
}

class RenamingMemoryAllocator private (
    space: MemorySpace,
    refTags: Set[MemoryTag],
    private val renameMap: mutable.Map[MemoryAddress, MemoryAddress]
) {
  def locate(refAddress: MemoryAddress): MemoryAddress =
    if (refTags.contains(refAddress.tag)) renameMap(refAddress) else refAddress

  def allocate(refAddress: MemoryAddress): MemoryAddress = {
    val (allocated, allocatedAddress) = allocateOrLocate(refAddress)
    require(allocated)
    allocatedAddress
  }

  def allocateOrLocate(
      refAddress: MemoryAddress
  ): (Boolean, MemoryAddress) =
    if (refTags.contains(refAddress.tag))
      renameMap.get(refAddress) match {
        case Some(allocatedAddress) =>
          (false, allocatedAddress)

        case None =>
          space.allocate(refAddress.ref, 1) match {
            case Some(allocatedSpan) =>
              val allocatedAddress = allocatedSpan(0)
              renameMap(refAddress) = allocatedAddress
              (true, allocatedAddress)

            case None =>
              throw new CompilerException(
                s"Insufficient ${space.name} memory to rename ${MemoryAddressHelper(refAddress)}"
              )
          }
      }
    else (false, refAddress)

  def free(): Unit = {
    space.free(renameMap.values.toArray)
    renameMap.clear()
  }

  def fork(space: MemorySpace): RenamingMemoryAllocator =
    new RenamingMemoryAllocator(space, refTags, renameMap.clone())
}
