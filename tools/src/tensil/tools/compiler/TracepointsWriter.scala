/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import scala.collection.mutable

import tensil.tools.{TracepointCondition, TracepointsMap}

class TracepointsWriter(
    tracepointConditions: Seq[TracepointCondition],
    resolveRefToObject: (MemoryRef) => Option[MemoryObject]
) {
  private val tracepoints =
    mutable.Map.empty[MemoryAddress, mutable.ArrayBuffer[MemoryObject]]

  def write(address: MemoryAddress): Unit = {
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

  def isEmpty = tracepoints.isEmpty
  def toMap: TracepointsMap = tracepoints.mapValues(_.toList).toMap
}
