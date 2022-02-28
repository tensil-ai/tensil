package tensil.tools.compiler

import scala.collection.mutable

import _root_.tensil.tools.{CompilerException}
import _root_.tensil.TablePrinter

class MemoryObjectAllocator(
    val allocator: MemorySpanAllocator,
    refBase: MemoryRef = 0
) {
  private case class Allocation(
      obj: MemoryObject,
      ref: MemoryRef,
      consumers: Seq[String]
  ) {
    val currentConsumers: mutable.ArrayBuffer[String] =
      mutable.ArrayBuffer.empty[String]

    currentConsumers ++= consumers
  }
  private val allocations = mutable.Map.empty[String, Allocation]
  private val refs        = mutable.ArrayBuffer.empty[Allocation]

  def reportObjects(): Unit = {
    val tp = new TablePrinter(Some("MEMORY OBJECTS"))

    for ((name, allocation) <- allocations)
      tp.addNamedLine(
        name,
        allocation.obj.dims,
        allocation.consumers.toIndexedSeq
      )

    print(tp)
  }

  def reportSpans() = allocator.reportSpans()

  def allocateObject(
      space: MemorySpace,
      name: String,
      dims: MemoryDimensions,
      consumers: Seq[String]
  ): MemoryObject =
    trackAllocationAndMakeObject(
      name,
      dims,
      consumers,
      allocator.allocate(space, _, dims.sizeVectors)
    )

  def blendObjects(
      name: String,
      dims: MemoryDimensions,
      consumers: Seq[String],
      blendeeNames: Seq[String],
      blendedSpan: MemorySpan
  ): MemoryObject =
    trackAllocationAndMakeObject(
      name,
      dims,
      consumers,
      allocator.blend(_, blendeeNames.map(allocations(_).ref), blendedSpan)
    )

  def hasObject(name: String): Boolean = allocations.contains(name)

  def resolveRefToObject(ref: MemoryRef): Option[MemoryObject] = {
    val i = ref - refBase
    if (refs.isDefinedAt(i)) Some(refs(i).obj) else None
  }

  def consumeObject(name: String, consumers: Seq[String]): MemoryObject = {
    allocations.get(name) match {
      case Some(allocation) =>
        allocation.currentConsumers --= consumers
        allocation.obj
      case None =>
        throw new CompilerException(s"Unresolved memory object ${name}")
    }
  }

  def freeConsumedObjects(spaces: Seq[MemorySpace]): Unit = {
    for (
      (name, allocation) <- allocations.filter(_._2.currentConsumers.isEmpty)
    ) {
      allocator.free(spaces, allocation.ref)
      allocations.remove(name)
    }
  }

  private def trackAllocationAndMakeObject(
      name: String,
      dims: MemoryDimensions,
      consumers: Seq[String],
      span: (MemoryRef) => MemorySpan
  ): MemoryObject = {
    val ref        = (refBase + refs.size).toShort
    val obj        = MemoryObject(name, span(ref), dims)
    val allocation = Allocation(obj, ref, consumers)

    refs += allocation
    allocations(name) = allocation

    obj
  }
}
