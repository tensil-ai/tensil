package tf2rtl.tools.compiler

case class MemoryObject(
    name: String,
    span: MemorySpan,
    dims: MemoryDimensions
) {
  def mkAddress(offset: Int): MemoryAddress =
    span(offset)

  def mkSub(
      name: String,
      offset: Int,
      dims: MemoryDimensions,
      repeats: Int = 1
  ): MemoryObject = {
    val subAddresses =
      Seq
        .fill(repeats)(span.slice(offset, (offset + dims.sizeVectors)))
        .flatten
        .toArray

    MemoryObject(
      name,
      subAddresses,
      dims
    )
  }
}

case class MemoryOptionalInputOutputObjects(
    input: Option[MemoryObject],
    output: MemoryObject
) {}
