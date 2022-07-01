/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.formal.tcu

import chisel3._
import chisel3.experimental.{verification => v}
import tensil.formal._
import tensil.tcu._
import tensil.util
import tensil.mem.MemKind.BlockRAM
import chisel3.stage.ChiselGeneratorAnnotation
import tensil.PlatformConfig

class AccumulatorFormal extends Formal {
  implicit val platformConfig =
    PlatformConfig.default.copy(accumulatorMemKind = BlockRAM)
  val m  = Module(new Accumulator(SInt(2.W), 2, 2))
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

object AccumulatorFormal extends App {
  util.emitToBuildDir(new AccumulatorFormal)
  Symbiyosys.emitConfig("AccumulatorFormal")
}
