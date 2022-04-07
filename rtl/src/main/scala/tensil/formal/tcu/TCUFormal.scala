/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.formal.tcu

import chisel3._
import chisel3.experimental.{verification => v}
import tensil.tcu._
import tensil.formal._
import tensil.tcu.instruction.Opcode
import tensil.tcu.instruction.MatMulFlags
import tensil.tcu.instruction.LoadWeightFlags
import tensil.tcu.instruction.SIMDFlags
import tensil.tcu.instruction.MatMulArgs
import tensil.InstructionLayout
import tensil.tcu.instruction.{DataMoveArgs, DataMoveKind}
import tensil.tcu.instruction.LoadWeightArgs
import tensil.tcu.instruction.SIMDArgs
import chisel3.util.Queue

class TCUFormal extends Formal {
  val arch   = tensil.Architecture.formal
  val layout = InstructionLayout(arch)
  val m      = Module(new TCU(SInt(2.W), layout))
  val io     = IO(m.io.cloneType)
  io <> m.io

  val instructionQueue = Queue(io.instruction, 1, flow = true)
  m.io.instruction <> instructionQueue

  val instructionDataIn = Node(
    m.io.instruction,
    filter =
      m.io.instruction.bits.opcode === Opcode.DataMove && m.io.instruction.bits.flags === DataMoveKind.dram0ToMemory
  )
  val instructionDataOut = Node(
    m.io.instruction,
    filter =
      m.io.instruction.bits.opcode === Opcode.DataMove && m.io.instruction.bits.flags === DataMoveKind.memoryToDram0
  )
  val instructionWeightsIn = Node(
    m.io.instruction,
    filter =
      m.io.instruction.bits.opcode === Opcode.DataMove && m.io.instruction.bits.flags === DataMoveKind.dram1ToMemory
  )

  val dram0Control = Node(m.io.dram0.control)
  val dram0DataIn  = Node(m.io.dram0.dataIn)
  val dram0DataOut = Node(m.io.dram0.dataOut)

  val dram1Control = Node(m.io.dram1.control)
  val dram1DataIn  = Node(m.io.dram1.dataIn)

  depends(dram0Control, instructionDataIn)
  depends(dram0Control, instructionDataOut)
  // depends(dram0DataIn, instructionDataIn)
  depends(dram0DataOut, instructionDataOut)

  depends(dram1Control, instructionWeightsIn)
  // depends(dram1DataIn, instructionWeightsIn)

  assertNoDeadlock()
}

object TCUFormal extends App {
  tensil.util.emitToBuildDir(new TCUFormal)
  Symbiyosys.emitConfig("TCUFormal")
}
