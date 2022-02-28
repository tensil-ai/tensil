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
}
