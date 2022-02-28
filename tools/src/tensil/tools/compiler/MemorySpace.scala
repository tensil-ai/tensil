package tensil.tools.compiler

class MemorySpace(
    val name: String,
    val tag: MemoryTag,
    val depth: MemoryAddressRaw
) {
  private var aggSizeVar: MemoryAddressRaw = MemoryAddressRaw.Zero
  private var maxSizeVar: MemoryAddressRaw = MemoryAddressRaw.Zero
  private var rawFreeSpan: Array[MemoryAddressRaw] =
    (MemoryAddressRaw.Zero until depth).toArray

  def allocate(
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

  def free(span: MemorySpan): Unit = {
    rawFreeSpan =
      (span.filter(a => a.tag == tag).map(_.raw) ++ rawFreeSpan).sorted.toArray
  }

  def maxSize: MemoryAddressRaw = maxSizeVar
  def aggSize: MemoryAddressRaw = aggSizeVar
}
