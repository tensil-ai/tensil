package tf2rtl.mem

import chisel3._
import chiseltest._
import tf2rtl.FunUnitSpec

class DualPortMemSpec extends FunUnitSpec {
  implicit class DualPortMemHelper[T <: Data](m: DualPortMem[T]) {
    def setClocks(): Unit = {
      m.io.portA.control.setSourceClock(m.clock)
      m.io.portA.input.setSourceClock(m.clock)
      m.io.portA.output.setSinkClock(m.clock)
      m.io.portB.control.setSourceClock(m.clock)
      m.io.portB.input.setSourceClock(m.clock)
      m.io.portB.output.setSinkClock(m.clock)
    }

    def writeA(address: UInt, value: T): Unit =
      write(m.io.portA, address, value, portName = "a")
    def writeB(address: UInt, value: T): Unit =
      write(m.io.portB, address, value, portName = "b")

    def write(
        port: Port[T],
        address: UInt,
        value: T,
        portName: String
    ): Unit = {
      thread(portName + ".control") {
        port.control.enqueue(MemControl(m.depth)(address, true.B))
      }
      thread(portName + ".input") {
        port.input.enqueue(value)
      }
    }

    def readA(address: UInt, expected: T): Unit =
      read(m.io.portA, address, expected, portName = "a")
    def readB(address: UInt, expected: T): Unit =
      read(m.io.portB, address, expected, portName = "b")

    def read(
        port: Port[T],
        address: UInt,
        expected: T,
        portName: String
    ): Unit = {
      thread(portName + ".control") {
        port.control.enqueue(MemControl(m.depth)(address, false.B))
      }
      thread(portName + ".output") {
        port.output.expectDequeue(expected)
      }
    }
  }

  describe("DualPortMem") {
    describe("when gen = UInt(8.W) and depth = 32") {
      val gen   = UInt(8.W)
      val depth = 32

      it("should perform a single read") {
        decoupledTest(new DualPortMem(gen, depth)) { m =>
          m.setClocks()

          m.io.portA.control.enqueue(
            MemControl(m.depth, 0.U, 0.U, false.B)
          )

          m.clock.step(100)

          m.io.portA.output.expectDequeue(0.U)
        }
      }

      it("should read and write data at each port") {
        decoupledTest(new DualPortMem(gen, depth)) { m =>
          m.setClocks()

          m.writeA(3.U, 123.U)
          m.writeA(7.U, 231.U)
          m.readA(3.U, 123.U)
          m.readA(7.U, 231.U)

          m.writeB(4.U, 123.U)
          m.writeB(8.U, 231.U)
          m.readB(4.U, 123.U)
          m.readB(8.U, 231.U)
        }
      }

      it("should interleave reads and writes") {
        decoupledTest(new DualPortMem(gen, depth)) { m =>
          m.setClocks()

          m.writeA(3.U, 123.U)
          m.readA(3.U, 123.U)
          m.writeA(7.U, 231.U)
          m.readA(7.U, 231.U)

          m.writeB(4.U, 123.U)
          m.readB(4.U, 123.U)
          m.writeB(8.U, 231.U)
          m.readB(8.U, 231.U)
        }
      }

      it("should share data across ports") {
        decoupledTest(new DualPortMem(gen, depth)) { m =>
          m.setClocks()

          m.writeA(0.U, 123.U)
          m.writeB(0.U, 231.U)
          thread("read") {
            m.clock.step(10)
            m.readA(0.U, 231.U)
          }

          m.writeB(1.U, 123.U)
          m.writeA(1.U, 231.U)
          thread("read") {
            m.clock.step(10)
            m.readB(1.U, 231.U)
          }
        }
      }

      it("should write to then read from both ports concurrently") {
        decoupledTest(new DualPortMem(gen, depth)) { m =>
          m.setClocks()

          thread("a") {
            m.writeA(0.U, 123.U)
            m.readA(0.U, 123.U)
          }

          thread("b") {
            m.writeB(1.U, 231.U)
            m.readB(1.U, 231.U)
          }
        }
      }
    }
  }
}
