package tensil.tools.compiler

import scala.collection.mutable

class InfiniteMemoryObjectAllocator(refBase: MemoryRef = 0) {
  private val refs = mutable.ArrayBuffer.empty[MemoryObject]

  def allocateObject(
      space: InfiniteMemorySpace,
      name: String,
      dims: MemoryDimensions
  ): MemoryObject = {
    val ref = (refBase + refs.size).toShort
    val obj = MemoryObject(
      name,
      space.allocate(ref, dims.sizeVectors),
      dims
    )

    refs += obj

    obj
  }

  def resolveRefToObject(ref: MemoryRef): Option[MemoryObject] = {
    val i = ref - refBase
    if (refs.isDefinedAt(i)) Some(refs(i)) else None
  }
}
