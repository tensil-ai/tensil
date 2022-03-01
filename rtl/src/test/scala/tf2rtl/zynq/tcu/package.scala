package tensil.zynq

import chisel3._
import chiseltest._
import tensil.InstructionLayout

package object tcu {
  implicit class AXIWrapperTCUHelper[T <: Data with Num[T]](
      m: AXIWrapperTCU[T]
  ) {
    def setClocks(): Unit = {
      m.instruction.setSourceClock(m.clock)
      m.status.setSinkClock(m.clock)
      m.dram0.writeAddress.setSinkClock(m.clock)
      m.dram0.writeData.setSinkClock(m.clock)
      m.dram0.writeResponse.setSourceClock(m.clock)
      m.dram0.readAddress.setSinkClock(m.clock)
      m.dram0.readData.setSourceClock(m.clock)
      m.dram1.writeAddress.setSinkClock(m.clock)
      m.dram1.writeData.setSinkClock(m.clock)
      m.dram1.writeResponse.setSourceClock(m.clock)
      m.dram1.readAddress.setSinkClock(m.clock)
      m.dram1.readData.setSourceClock(m.clock)
    }

    def setInstructionParameters(): InstructionLayout = {
      m.tcu.layout
    }
  }
}
