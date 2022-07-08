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
) extends mutable.Cloneable[RenamingMemoryAllocator] {
  def map = renameMap.toMap

  def locate(refAddress: MemoryAddress): MemoryAddress = renameMap(refAddress)

  def allocate(refAddress: MemoryAddress): MemoryAddress = {
    val (allocated, allocatedAddress) = allocateOrLocate(refAddress)
    require(allocated)
    allocatedAddress
  }

  def allocateOrLocate(
      refAddress: MemoryAddress
  ): (Boolean, MemoryAddress) =
    renameMap.get(refAddress) match {
      case Some(allocatedAddress) =>
        (false, allocatedAddress)

      case None =>
        require(refTags.contains(refAddress.tag))

        space.allocate(refAddress.ref, 1) match {
          case Some(allocatedSpan) =>
            val allocatedAddress = allocatedSpan(0)
            renameMap(refAddress) = allocatedAddress
            (true, allocatedAddress)

          case None =>
            throw new CompilerException(
              s"Insufficient ${space.name} memory to allocate ${refAddress}"
            )
        }
    }

  def freeTag(refTag: MemoryTag): Unit = {
    val (refAddresses, allocatedAddresses) = renameMap.filter {
      case (refAddress, allocatedAddress) => refAddress.tag == refTag
    }.unzip

    // REMOVE:
    println(s"BEFORE FREE TAG: ${renameMap.size}")

    space.free(allocatedAddresses.toArray)
    refAddresses.foreach(renameMap.remove(_))

    println(s"AFTER FREE TAG: ${renameMap.size}")
  }

  def freeSpan(refAddresses: MemorySpan): Unit = {
    val allocatedAddresses =
      refAddresses.map(renameMap.get(_)).filter(_.isDefined).map(_.get)

    // REMOVE:
    println(s"BEFORE FREE SPAN: ${renameMap.size}")

    space.free(allocatedAddresses.toArray)
    refAddresses.foreach(renameMap.remove(_))

    println(s"AFTER FREE SPAN: ${renameMap.size}")
  }

  override def clone(): RenamingMemoryAllocator =
    new RenamingMemoryAllocator(space.clone(), refTags, renameMap.clone())
}
