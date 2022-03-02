/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.Queue
import chisel3.util.log2Ceil
import tensil.util.zero
import tensil.util.decoupled.QueueWithReporting
import chisel3.util.DecoupledIO

class RequestSplitter(depth: Long, maxSize: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new MemControl(depth)))
    val out = Decoupled(new MemControl(depth))
  })

  val in               = io.in
  val sizeCounter      = RegInit(zero(io.in.bits.size))
  val sizeCounterValid = RegInit(false.B)
  val addressOffset    = RegInit(zero(io.in.bits.size))
  val address          = in.bits.address + addressOffset

  when(in.bits.size < maxSize.U) {
    io.out <> in
  }.otherwise {
    when(sizeCounterValid && sizeCounter < maxSize.U) {
      io.out.bits := MemControl(
        depth,
        address,
        sizeCounter,
        in.bits.write
      )
      io.out.valid := in.valid
      in.ready := io.out.ready
      when(in.valid && io.out.ready) {
        sizeCounterValid := false.B
        addressOffset := 0.U
      }
    }.otherwise {
      io.out.bits := MemControl(
        depth,
        address,
        (maxSize - 1).U,
        in.bits.write
      )
      io.out.valid := in.valid
      in.ready := false.B
      when(in.valid && io.out.ready) {
        when(sizeCounterValid) {
          sizeCounter := sizeCounter - maxSize.U
          addressOffset := addressOffset + maxSize.U
        }.otherwise {
          sizeCounter := in.bits.size - maxSize.U
          addressOffset := maxSize.U
          sizeCounterValid := true.B
        }
      }
    }
  }
}

object RequestSplitter {
  def apply(depth: Long, maxRequests: Int)(
      bus: DecoupledIO[MemControl]
  ): DecoupledIO[MemControl] = {
    val splitter = Module(new RequestSplitter(depth, maxRequests))
    splitter.io.in <> bus
    splitter.io.out
  }
}
