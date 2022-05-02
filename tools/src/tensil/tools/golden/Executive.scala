package tensil.tools.golden

import tensil.tools.compiler.{MemoryAddressRaw}

abstract trait Executive {
  def peekAccumulator(address: MemoryAddressRaw): Array[Float]
  def peekLocal(address: MemoryAddressRaw): Array[Float]
  def peekDRAM0(address: MemoryAddressRaw): Array[Float]
  def peekDRAM1(address: MemoryAddressRaw): Array[Float]
}
