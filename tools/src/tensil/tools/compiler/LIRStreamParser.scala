/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io._

import tensil.tools.{TraceContext}
import tensil.{Architecture, InstructionLayout}

class LIRStreamParser(arch: Architecture, stream: InputStream)
    extends LIRParser {
  val layout = new InstructionLayout(arch)
  val bytes =
    Array.fill[Byte](layout.instructionSizeBytes + 1)(0)
  var bytesRead: Option[Boolean] = None

  override def hasNext: Boolean = {
    if (!bytesRead.isDefined)
      bytesRead = Some(stream.read(bytes, 0, layout.instructionSizeBytes) != -1)

    bytesRead.get
  }

  override def parseNext(lir: LIR): Unit = {
    require(bytesRead.isDefined && bytesRead.get)

    val instruction      = BigInt(bytes.reverse)
    var decodedSize: Int = 0

    def decodeInstructionBits(bitsSize: Int) = {
      val bits =
        (instruction >> decodedSize) & ((BigInt(
          1
        ) << bitsSize) - BigInt(1))
      decodedSize += bitsSize
      bits.toLong
    }

    def skipBits(bitsSize: Int) = {
      decodedSize += bitsSize
    }

    def decodeAddressOperand0() = {
      val r = (
        decodeInstructionBits(layout.operand0AddressSizeBits),
        decodeInstructionBits(layout.stride0SizeBits).toInt
      )
      skipBits(
        layout.operand0SizeBits - (layout.operand0AddressSizeBits + layout.stride0SizeBits)
      )
      r
    }
    def decodeAddressOperand1() = {
      val r = (
        decodeInstructionBits(layout.operand1AddressSizeBits),
        decodeInstructionBits(layout.stride1SizeBits).toInt
      )
      skipBits(
        layout.operand1SizeBits - (layout.operand1AddressSizeBits + layout.stride1SizeBits)
      )
      r
    }
    def decodeSizeOperand1() = {
      decodeInstructionBits(layout.operand1SizeBits)
    }
    def decodeSizeOperand2() = {
      decodeInstructionBits(layout.operand2SizeBits)
    }
    def decodeSimdInstructionOperand2() = {
      val r = (
        decodeInstructionBits(layout.simdOperandSizeBits).toInt,
        decodeInstructionBits(layout.simdOperandSizeBits).toInt,
        decodeInstructionBits(layout.simdOperandSizeBits).toInt,
        decodeInstructionBits(layout.simdOpSizeBits).toByte
      )
      skipBits(layout.operand2SizeBits - layout.simdInstructionSizeBits)
      r
    }
    def decodeTidOperand() = {
      val r = decodeInstructionBits(layout.tidSizeBits).toInt
      skipBits(
        layout.operand0SizeBits - (layout.tidSizeBits)
      )
      r
    }

    val header = instruction >> layout.operandsSizeBits

    val tid: Int =
      (header >> (layout.opcodeSizeBits + layout.flagsSizeBits)).toByte
    val opcode: Int =
      ((header >> layout.flagsSizeBits) & ((1 << layout.opcodeSizeBits) - 1)).toByte
    val flags: Int = (header & ((1 << layout.flagsSizeBits) - 1)).toByte

    val r = if (opcode == Opcode.Wait) {
      val tidToWait = decodeTidOperand()
      skipBits(layout.operand1SizeBits + layout.operand2SizeBits)

      lir.emitWait(tidToWait, tid)
    } else if (opcode == Opcode.MatMul) {
      val (localAddress, localStride) = decodeAddressOperand0()
      val (accumulatorAddress, accumulatorStride) =
        decodeAddressOperand1()
      val size       = decodeSizeOperand2()
      val accumulate = (flags & MatMulFlags.Accumulate) != 0
      val localTag =
        if ((flags & MatMulFlags.Zeroes) != 0) MemoryTag.Zeroes
        else MemoryTag.Local

      lir.emitMatMul(
        accumulate,
        localStride,
        MemoryAddress(
          localTag,
          MemoryRef.Invalid,
          localAddress
        ),
        accumulatorStride,
        MemoryAddress(
          MemoryTag.Accumulators,
          MemoryRef.Invalid,
          accumulatorAddress
        ),
        size,
        tid
      )
    } else if (opcode == Opcode.DataMove) {
      val (localAddress, localStride) = decodeAddressOperand0()
      val (accumulatorOrDRAMAddress, accumulatorOrDRAMStride) =
        decodeAddressOperand1()
      val size = decodeSizeOperand2()
      val accumulate =
        flags == DataMoveFlags.LocalToAccumulatorAccumulate
      val toLocal = flags match {
        case DataMoveFlags.DRAM0ToLocal | DataMoveFlags.DRAM1ToLocal |
            DataMoveFlags.AccumulatorToLocal =>
          true
        case _ => false
      }
      val accumulatorOrDRAMTag = flags match {
        case DataMoveFlags.AccumulatorToLocal |
            DataMoveFlags.LocalToAccumulator |
            DataMoveFlags.LocalToAccumulatorAccumulate =>
          MemoryTag.Accumulators
        case DataMoveFlags.DRAM0ToLocal | DataMoveFlags.LocalToDRAM0 =>
          MemoryTag.Vars
        case DataMoveFlags.DRAM1ToLocal | DataMoveFlags.LocalToDRAM1 =>
          MemoryTag.Consts
      }

      lir.emitDataMove(
        toLocal,
        accumulate,
        localStride,
        MemoryAddress(
          MemoryTag.Local,
          MemoryRef.Invalid,
          localAddress
        ),
        accumulatorOrDRAMStride,
        MemoryAddress(
          accumulatorOrDRAMTag,
          MemoryRef.Invalid,
          accumulatorOrDRAMAddress
        ),
        size,
        tid
      )
    } else if (opcode == Opcode.LoadWeights) {
      val (localAddress, localStride) = decodeAddressOperand0()
      val size                        = decodeSizeOperand1()
      val localTag =
        if ((flags & LoadWeightsFlags.Zeroes) != 0) MemoryTag.Zeroes
        else MemoryTag.Local
      skipBits(layout.operand2SizeBits)

      lir.emitLoadWeights(
        localStride,
        MemoryAddress(localTag, MemoryRef.Invalid, localAddress),
        size,
        tid
      )

    } else if (opcode == Opcode.SIMD) {
      val (writeAccumulatorAddress, _) = decodeAddressOperand0()
      val (readAccumulatorAddress, _)  = decodeAddressOperand1()
      val (simdDestination, simdSourceRight, simdSourceLeft, simdOp) =
        decodeSimdInstructionOperand2()
      val accumulate = (flags & SIMDFlags.Accumulate) != 0
      val readAccumulatorTag =
        if ((flags & SIMDFlags.Read) != 0) MemoryTag.Accumulators
        else MemoryTag.Invalid
      val writeAccumulatorTag =
        if ((flags & SIMDFlags.Write) != 0) MemoryTag.Accumulators
        else MemoryTag.Invalid

      lir.emitSIMD(
        accumulate,
        simdOp,
        simdSourceLeft,
        simdSourceRight,
        simdDestination,
        MemoryAddress(
          writeAccumulatorTag,
          MemoryRef.Invalid,
          writeAccumulatorAddress
        ),
        MemoryAddress(
          readAccumulatorTag,
          MemoryRef.Invalid,
          readAccumulatorAddress
        ),
        tid
      )
    }

    require(decodedSize == layout.operandsSizeBits)

    bytesRead = None
  }
}
