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

  // v.assume(Opcode.all.map(io.instruction.bits.opcode === _).reduce(_ || _))

  // v.assume(io.instruction.bits.opcode =/= Opcode.NoOp)
  // v.assume(io.instruction.bits.opcode =/= Opcode.Configure)

  // v.cover(io.instruction.bits.opcode === Opcode.MatMul)
  // when(io.instruction.bits.opcode === Opcode.MatMul) {
  //   v.assume(MatMulFlags.isValid(io.instruction.bits.flags))
  //   val args = Wire(
  //     new MatMulArgs(layout)
  //   )
  //   args := io.instruction.bits.arguments.asTypeOf(args)
  //   v.assume(args.accAddress === 0.U)
  //   v.assume(args.memAddress === 0.U)
  // }

  // v.cover(io.instruction.bits.opcode === Opcode.DataMove)
  // when(io.instruction.bits.opcode === Opcode.DataMove) {
  //   v.assume(DataMoveKind.isValid(io.instruction.bits.flags))
  //   val args = Wire(
  //     new DataMoveArgs(layout)
  //   )
  //   args := io.instruction.bits.arguments.asTypeOf(args)
  //   v.assume(args.accAddress === 0.U)
  //   v.assume(args.memAddress === 0.U)
  // }

  // v.cover(io.instruction.bits.opcode === Opcode.LoadWeights)
  // when(io.instruction.bits.opcode === Opcode.LoadWeights) {
  //   v.assume(LoadWeightFlags.isValid(io.instruction.bits.flags))
  //   val args = Wire(
  //     new LoadWeightArgs(layout)
  //   )
  //   args := io.instruction.bits.arguments.asTypeOf(args)
  //   v.assume(args.address === 0.U)
  // }

  // v.cover(io.instruction.bits.opcode === Opcode.SIMD)
  // when(io.instruction.bits.opcode === Opcode.SIMD) {
  //   v.assume(SIMDFlags.isValid(io.instruction.bits.flags))
  //   val args = Wire(
  //     new SIMDArgs(layout)
  //   )
  //   args := io.instruction.bits.arguments.asTypeOf(args)
  //   v.assume(args.accReadAddress === 0.U)
  //   v.assume(args.accWriteAddress === 0.U)
  // }

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
