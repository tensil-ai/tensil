/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools

package object compiler {
  type MemoryTag          = Byte
  type MemoryRef          = Short
  type MemoryAddressRaw   = Long
  type MemoryAddress      = Long
  type MemorySpan         = Array[MemoryAddress]
  type EmitResult         = Option[SchedulerResult]
  type Emitter            = EmitContext => EmitResult
  type InstructionAddress = Long
  type BackendSegmentKey  = (Int, Int, Int, Int)

  implicit class MemoryAddressHelper(val address: MemoryAddress) {
    def tag: MemoryTag =
      ((address >> MemoryAddress.tagShift) & MemoryAddress.tagMask).toByte

    def ref: MemoryRef =
      ((address >> MemoryAddress.refShift) & MemoryAddress.refMask).toShort

    def raw: MemoryAddressRaw = address & MemoryAddress.rawMask

    override def toString() =
      tag match {
        case MemoryTag.Invalid => "Invalid"
        case MemoryTag.Zeroes  => "Zeroes"
        case tag               => f"${MemoryTag.toString(tag)}(${raw})"
      }
  }

  implicit class BackendSegmentKeyHelper(val key: BackendSegmentKey) {
    override def toString() =
      if (key._4 == BackendSegmentKey.Init)
        s"LAYER ${key._1}, STAGE ${key._2}, INIT"
      else {
        def kindToString(kind: Int) =
          kind match {
            case BackendSegmentKey.Load    => "LOAD"
            case BackendSegmentKey.Compute => "COMPUTE"
            case BackendSegmentKey.Save    => "SAVE"
          }
        s"LAYER ${key._1}, STAGE ${key._2}, PARTITION ${key._3}, ${kindToString(key._4)}"
      }
  }
}
