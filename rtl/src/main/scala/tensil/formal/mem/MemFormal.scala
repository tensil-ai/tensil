/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.formal.mem

import chisel3._
import chisel3.experimental.{verification => v}
import tensil.mem.Mem
import tensil.mem.MemKind.XilinxBlockRAM
import tensil.formal._
import firrtl.MemKind
import tensil.PlatformConfig

class MemFormal extends Formal {
  implicit val platformConfig =
    PlatformConfig.default.copy(memKind = XilinxBlockRAM)
  val m = Module(new Mem(SInt(2.W), 2, inQueueFlow = true))

  val io = IO(m.io.cloneType)
  io <> m.io

  val controlRead  = Node(m.io.control, filter = !m.io.control.bits.write)
  val controlWrite = Node(m.io.control, filter = m.io.control.bits.write)
  val input        = Node(m.io.input)
  val output       = Node(m.io.output)
  val wrote        = Node(m.io.wrote)

  depends(output, controlRead)
  depends(wrote, controlWrite)
  depends(wrote, input)

  assertNoDeadlock()
}

object MemFormal extends App {
  tensil.util.emitToBuildDir(new MemFormal)
  Symbiyosys.emitConfig("MemFormal")
}
