/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

import chisel3._
import chisel3.util.{Decoupled, Queue, log2Ceil}
import tensil.util.{Delay, reportThroughput}
import tensil.util.decoupled.Counter
import tensil.util.decoupled.QueueWithReporting
import tensil.blackbox.{DualPortBlockRAM, xilinx}
import tensil.util

class DualPortMem[T <: Data](
    val gen: T,
    val depth: Long,
    memKind: MemKind.Type,
    debug: Boolean = false,
    name: String = "mem",
) extends Module {
  val addressType = UInt(log2Ceil(depth).W)

  val io = IO(new Bundle {
    val portA          = new Port(gen, depth)
    val portB          = new Port(gen, depth)
    val tracepoint     = Input(Bool())
    val programCounter = Input(UInt(32.W))
  })

  dontTouch(io.tracepoint)
  dontTouch(io.programCounter)

  val mem = Module(new InnerDualPortMem(gen, depth, memKind))

  connectPort(io.portA, mem.io.portA, "A")
  connectPort(io.portB, mem.io.portB, "B")

  def connectPort(
      port: Port[T],
      inner: InnerPort[T],
      portName: String
  ): Unit = {
    val control = port.control
    val input   = port.input

    val readDelay        = 1
    val outputBufferSize = 2
    val output = Module(
      new Queue(
        chiselTypeOf(port.output.bits),
        readDelay + outputBufferSize,
        flow = true
      )
    )
    val outputReady = output.io.count < outputBufferSize.U

    inner.address := control.bits.address

    when(control.bits.write) {
      control.ready := input.valid
      inner.write.enable := control.valid && input.valid
      inner.read.enable := false.B
    }.otherwise {
      control.ready := outputReady
      inner.write.enable := false.B
      inner.read.enable := control.valid && outputReady
    }

    output.io.enq.bits := inner.read.data
    output.io.enq.valid := Delay(inner.read.enable, readDelay)

    port.output <> output.io.deq

    inner.write.data := input.bits
    input.ready := control.valid && control.bits.write

    if (debug) {
      when(control.valid && control.ready) {
        when(!control.bits.write) {
          printf(p"Mem: read $name.$portName[${control.bits.address}]\n")
        }.otherwise {
          printf(
            p"Mem: wrote $name.portName[${control.bits.address}] = 0x${Hexadecimal(input.bits.asUInt())}\n"
          )
        }
      }
    }

    port.status.bits := control.bits
    port.status.valid := control.valid && control.ready

    port.inputStatus.bits := input.bits
    port.inputStatus.valid := input.valid && input.ready

    val wrote = OutQueue(port.wrote, 2, flow = true)
    wrote.bits := true.B
    wrote.valid := inner.write.enable
    // TODO we should wait for wrote ready somewhere here
  }

  class InnerDualPortMem[T <: Data](
      gen: T,
      depth: Long,
      kind: MemKind.Type,
  ) extends Module {
    val io = IO(new Bundle {
      val portA = new InnerPort(gen, depth)
      val portB = new InnerPort(gen, depth)
    })

    kind match {
      case MemKind.RegisterBank => {
        if (depth > Int.MaxValue) {
          throw new Exception(
            s"depth $depth is too large for register bank mem type"
          )
        }
        val mem     = RegInit(VecInit(Seq.fill(depth.toInt)(util.zero(gen))))
        val outputA = RegInit(util.zero(gen))
        io.portA.read.data := outputA
        when(io.portA.write.enable) {
          when(io.portA.read.enable) {
            outputA := mem(io.portA.address)
          }.otherwise {
            mem(io.portA.address) := io.portA.write.data
          }
        }.otherwise {
          when(io.portA.read.enable) {
            outputA := mem(io.portA.address)
          }.otherwise {
            // do nothing
          }
        }
        val outputB = RegInit(util.zero(gen))
        io.portB.read.data := outputB
        when(io.portB.write.enable) {
          when(io.portB.read.enable) {
            outputB := mem(io.portB.address)
          }.otherwise {
            mem(io.portB.address) := io.portB.write.data
          }
        }.otherwise {
          when(io.portB.read.enable) {
            outputB := mem(io.portB.address)
          }.otherwise {
            // do nothing
          }
        }
      }
      case MemKind.ChiselSyncReadMem => {

        /**
          * This approach will produce undefined behaviour
          * when a read and write to the same address are
          * requested on the same cycle.
          *
          * Yet this seems to be the recommened approach
          * to dual-port memory in Chisel. See:
          *
          * https://github.com/chipsalliance/chisel3/issues/1788
          */

        val mem = SyncReadMem(depth, gen, SyncReadMem.ReadFirst)

        for (port <- Array(io.portA, io.portB)) {
          port.read.data <> mem.read(port.address, port.read.enable)
          when(port.write.enable) {
            mem.write(port.address, port.write.data)
          }
        }
      }
      case MemKind.BlockRAM => {
        val mem = Module(new DualPortBlockRAM(gen.getWidth, depth))

        mem.io.clka := clock.asBool
        mem.io.ena := !reset.asBool
        mem.io.addra := io.portA.address
        io.portA.read.data := mem.io.doa.asTypeOf(io.portA.read.data)
        mem.io.wea := io.portA.write.enable
        mem.io.dia := io.portA.write.data.asTypeOf(mem.io.dia)

        mem.io.clkb := clock.asBool
        mem.io.enb := !reset.asBool
        mem.io.addrb := io.portB.address
        io.portB.read.data := mem.io.dob.asTypeOf(io.portB.read.data)
        mem.io.web := io.portB.write.enable
        mem.io.dib := io.portB.write.data.asTypeOf(mem.io.dib)
      }
      case MemKind.XilinxBRAMMacro | MemKind.XilinxURAMMacro => {
        val mem = Module(
          new xilinx.RAMMacro(
            gen.getWidth,
            depth,
            kind match {
              case MemKind.XilinxBRAMMacro => xilinx.RAMMacro.BlockPrimitive
              case MemKind.XilinxURAMMacro => xilinx.RAMMacro.UltraPrimitive
            }
          )
        )

        mem.io.clka := clock.asBool
        mem.io.ena := !reset.asBool
        mem.io.addra := io.portA.address
        io.portA.read.data := mem.io.doa.asTypeOf(io.portA.read.data)
        mem.io.wea := io.portA.write.enable
        mem.io.dia := io.portA.write.data.asTypeOf(mem.io.dia)

        mem.io.clkb := clock.asBool
        mem.io.enb := !reset.asBool
        mem.io.addrb := io.portB.address
        io.portB.read.data := mem.io.dob.asTypeOf(io.portB.read.data)
        mem.io.web := io.portB.write.enable
        mem.io.dib := io.portB.write.data.asTypeOf(mem.io.dib)
      }
    }
  }
}
