/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

import chisel3._
import chisel3.util.{Decoupled, Queue, log2Ceil}
import tensil.util.Delay
import tensil.util.decoupled.QueueWithReporting
import tensil.blackbox.BlockRAM
import tensil.util
import chisel3.util.DecoupledIO
import chisel3.util.TransitName

object OutQueue {
  def apply[T <: Data](
      d: DecoupledIO[T],
      n: Int,
      flow: Boolean = false,
      pipe: Boolean = false
  ): DecoupledIO[T] = {
    val q = Module(new Queue(chiselTypeOf(d.bits), n, flow = flow, pipe = pipe))
    d <> q.io.deq
    TransitName(q.io.enq, q)
  }
}

class Mem[T <: Data](
    val gen: T,
    val depth: Long,
    memKind: MemKind.Type,
    debug: Boolean = false,
    name: String = "mem",
    controlQueueSize: Int = 2,
    inQueueFlow: Boolean = false,
) extends Module {
  val addressType = UInt(log2Ceil(depth).W)

  val io = IO(new Port(gen, depth))

  val mem = Module(new InnerMem(gen, depth, memKind))

  val control = io.control
  val input   = io.input

  val readDelay        = 1
  val outputBufferSize = 2
  val output = Module(
    new Queue(
      chiselTypeOf(io.output.bits),
      readDelay + outputBufferSize,
      flow = true
    )
  )
  val outputReady = output.io.count < outputBufferSize.U

  mem.io.address := control.bits.address

  output.io.enq.bits := mem.io.read.data
  mem.io.read.enable := control.valid && !control.bits.write && outputReady
  output.io.enq.valid := Delay(mem.io.read.enable, readDelay)

  io.output <> output.io.deq

  mem.io.write.data := input.bits
  mem.io.write.enable := control.valid && control.bits.write && input.valid
  input.ready := control.valid && control.bits.write
  control.ready := (!control.bits.write && outputReady) || (control.bits.write && input.valid)

  val wrote = OutQueue(io.wrote, 2, flow = true)
  wrote.bits := true.B
  wrote.valid := mem.io.write.enable
  // TODO we should wait for wrote ready somewhere here

  if (debug) {
    when(control.valid && control.ready) {
      when(!control.bits.write) {
        printf(p"Mem: read $name[${control.bits.address}]\n")
      }.otherwise {
        printf(
          p"Mem: wrote $name[${control.bits.address}] = 0x${Hexadecimal(input.bits.asUInt())}\n"
        )
      }
    }
  }

  io.status.bits := control.bits
  io.status.valid := control.valid && control.ready

  io.inputStatus.bits := input.bits
  io.inputStatus.valid := input.valid && input.ready

  class InnerMem[T <: Data](
      gen: T,
      depth: Long,
      kind: MemKind.Type,
  ) extends Module {
    val io = IO(new Bundle {
      val address = Input(UInt(log2Ceil(depth).W))
      val read = new Bundle {
        val enable = Input(Bool())
        val data   = Output(gen)
      }
      val write = new Bundle {
        val enable = Input(Bool())
        val data   = Input(gen)
      }
    })

    kind match {
      case MemKind.RegisterBank => {
        if (depth > Int.MaxValue) {
          throw new Exception(
            s"depth $depth is too large for register bank mem type"
          )
        }
        val mem    = RegInit(VecInit(Seq.fill(depth.toInt)(util.zero(gen))))
        val output = RegInit(util.zero(gen))
        io.read.data := output
        when(io.write.enable) {
          when(io.read.enable) {
            // TODO here is where we decide if reads or writes get priority
            //      for now we'll say that reads win
            output := mem(io.address)
          }.otherwise {
            mem(io.address) := io.write.data
          }
        }.otherwise {
          when(io.read.enable) {
            output := mem(io.address)
          }.otherwise {
            // do nothing
          }
        }
      }
      case MemKind.ChiselSyncReadMem => {
        val mem = SyncReadMem(depth, gen, SyncReadMem.ReadFirst)
        io.read.data <> mem.read(io.address, io.read.enable)
        when(io.write.enable) {
          mem.write(io.address, io.write.data)
        }
      }
      case MemKind.BlockRAM => {
        val mem = Module(new BlockRAM(gen.getWidth, depth))
        mem.io.clk := clock.asBool
        mem.io.en := !reset.asBool
        mem.io.addr := io.address
        io.read.data := mem.io.dout.asTypeOf(io.read.data)
        mem.io.we := io.write.enable
        mem.io.di := io.write.data.asTypeOf(mem.io.di)
      }
      case MemKind.XilinxBRAMMacro | MemKind.XilinxURAMMacro => {
        throw new Exception("Only dual port Xilinx macros are supported")
      }
    }
  }
}
