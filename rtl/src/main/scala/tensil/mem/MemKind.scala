package tensil.mem

object MemKind extends Enumeration {
  type MemKind = Value
  val RegisterBank, ChiselSyncReadMem, XilinxRAMMacro, XilinxBlockRAM = Value
}
