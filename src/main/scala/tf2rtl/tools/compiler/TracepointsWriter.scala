package tf2rtl.tools.compiler

import scala.collection.mutable

import tf2rtl.tools.{TracepointCondition, TracepointsMap}

class TracepointsWriter(
    tracepointConditions: Seq[TracepointCondition],
    resolveRefToObject: (MemoryRef) => Option[MemoryObject]
) {
  private val tracepoints =
    mutable.Map.empty[MemoryAddress, mutable.ArrayBuffer[MemoryObject]]

  def emitWrite(address: MemoryAddress): Unit = {
    if (address.ref != MemoryRef.Invalid) {
      val obj = resolveRefToObject(address.ref)

      if (
        obj.isDefined &&
        tracepointConditions
          .exists(c =>
            obj.get.name.startsWith(c.prefix) && c.tag == address.tag
          )
      )
        tracepoints.getOrElseUpdate(
          address,
          mutable.ArrayBuffer.empty[MemoryObject]
        ) += obj.get
    }
  }

  def toMap(): TracepointsMap = tracepoints.mapValues(_.toList).toMap
}
