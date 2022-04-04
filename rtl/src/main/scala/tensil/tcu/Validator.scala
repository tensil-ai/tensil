/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import chisel3.util.Valid
import tensil.mem.MemControl
import tensil.InstructionLayout
import tensil.tcu.instruction.{
  DataMoveArgs,
  DataMoveFlags,
  DataMoveKind,
  Instruction,
  LoadWeightArgs,
  LoadWeightFlags,
  MatMulArgs,
  MatMulFlags,
  Opcode,
  SIMDArgs,
  SIMDFlags
}

class Validator(val layout: InstructionLayout) extends Module {
  val io = IO(new Bundle {
    val instruction =
      Flipped(Valid(new Instruction(layout.instructionSizeBytes * 8)))
    val error = Output(Bool())
  })

  val instruction = io.instruction

  when(instruction.valid) {
    when(instruction.bits.opcode === Opcode.MatMul) {
      val flags = Wire(new MatMulFlags)
      val args  = Wire(new MatMulArgs(layout))

      flags := instruction.bits.flags.asTypeOf(flags)
      args := instruction.bits.arguments.asTypeOf(args)

      when(args.size >= layout.arch.accumulatorDepth.U) {
        io.error := true.B
      }.elsewhen(args.accAddress >= layout.arch.accumulatorDepth.U) {
        io.error := true.B
      }.elsewhen(args.memAddress >= layout.arch.localDepth.U) {
        io.error := true.B
      }.elsewhen(
        args.accAddress + args.size >= layout.arch.accumulatorDepth.U
      ) {
        io.error := true.B
      }.elsewhen(args.memAddress + args.size >= layout.arch.localDepth.U) {
        io.error := true.B
      }.elsewhen(flags._unused =/= 0.U) {
        io.error := true.B
      }.otherwise {
        io.error := false.B
      }
    }.elsewhen(instruction.bits.opcode === Opcode.LoadWeights) {
      val flags = Wire(new LoadWeightFlags)
      val args  = Wire(new LoadWeightArgs(layout))

      flags := instruction.bits.flags.asTypeOf(flags)
      args := instruction.bits.arguments.asTypeOf(args)

      when(args.size > (layout.arch.arraySize + 1).U) {
        io.error := true.B
      }.elsewhen(args.address >= layout.arch.localDepth.U) {
        io.error := true.B
      }.elsewhen(args.address + args.size >= layout.arch.localDepth.U) {
        io.error := true.B
      }.elsewhen(flags._unused =/= 0.U) {
        io.error := true.B
      }.otherwise {
        io.error := false.B
      }
    }.elsewhen(instruction.bits.opcode === Opcode.DataMove) {
      val args  = Wire(new DataMoveArgs(layout))
      val flags = Wire(new DataMoveFlags)

      args := instruction.bits.arguments.asTypeOf(args)
      flags := instruction.bits.flags.asTypeOf(flags)

      when(
        flags.kind === DataMoveKind.dram0ToMemory ||
          flags.kind === DataMoveKind.memoryToDram0 ||
          flags.kind === DataMoveKind.dram1ToMemory ||
          flags.kind === DataMoveKind.memoryToDram1
      ) {
        when(args.size >= layout.arch.localDepth.U) {
          io.error := true.B
        }.elsewhen(args.memAddress >= layout.arch.localDepth.U) {
          io.error := true.B
        }.elsewhen(args.memAddress + args.size >= layout.arch.localDepth.U) {
          io.error := true.B
        }.otherwise {
          io.error := false.B
        }
      }.elsewhen(
        flags.kind === DataMoveKind.accumulatorToMemory ||
          flags.kind === DataMoveKind.memoryToAccumulator ||
          flags.kind === DataMoveKind.memoryToAccumulatorAccumulate
      ) {
        when(args.size >= layout.arch.accumulatorDepth.U) {
          io.error := true.B
        }.elsewhen(args.accAddress >= layout.arch.accumulatorDepth.U) {
          io.error := true.B
        }.elsewhen(args.memAddress >= layout.arch.localDepth.U) {
          io.error := true.B
        }.elsewhen(
          args.accAddress + args.size >= layout.arch.accumulatorDepth.U
        ) {
          io.error := true.B
        }.elsewhen(args.memAddress + args.size >= layout.arch.localDepth.U) {
          io.error := true.B
        }.otherwise {
          io.error := false.B
        }
      }.otherwise {
        io.error := true.B
      }

    }.elsewhen(instruction.bits.opcode === Opcode.SIMD) {
      val flags = Wire(new SIMDFlags)
      val args =
        Wire(new SIMDArgs(layout))
      flags := instruction.bits.flags.asTypeOf(flags)
      args := instruction.bits.arguments.asTypeOf(args)

      when(args.accReadAddress >= layout.arch.accumulatorDepth.U) {
        io.error := true.B
      }.elsewhen(args.accWriteAddress >= layout.arch.accumulatorDepth.U) {
        io.error := true.B
      }.elsewhen(args.instruction.op >= simd.Op.numOps.U) {
        io.error := true.B
      }.elsewhen(
        args.instruction.sourceLeft > layout.arch.simdRegistersDepth.U
      ) {
        io.error := true.B
      }.elsewhen(
        args.instruction.sourceRight > layout.arch.simdRegistersDepth.U
      ) {
        io.error := true.B
      }.elsewhen(args.instruction.dest > layout.arch.simdRegistersDepth.U) {
        io.error := true.B
      }.elsewhen(flags._unused =/= 0.U) {
        io.error := true.B
      }.elsewhen(
        flags.read && !(args.instruction.sourceLeft === 0.U || args.instruction.sourceRight === 0.U)
      ) {
        // We have indicated that the accumulator should read but the result
        // will not be consumed by the ALUs. This is an error.
        io.error := true.B
      }.elsewhen(
        !flags.read && (args.instruction.sourceLeft === 0.U || args.instruction.sourceRight === 0.U)
      ) {
        // We have indicated that the accumulator should not read but the ALUs
        // are expecting to consume data. This is an error.
        // TODO don't raise error on unary operation where only sourceRight == 0
        io.error := true.B
      }.otherwise {
        io.error := false.B
      }
    }.elsewhen(instruction.bits.opcode === Opcode.Configure) {
      io.error := false.B
    }.elsewhen(instruction.bits.opcode === Opcode.NoOp) {
      io.error := false.B
    }.otherwise {
      io.error := true.B
    }
  }.otherwise {
    io.error := false.B
  }
}
