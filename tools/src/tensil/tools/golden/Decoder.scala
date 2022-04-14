/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.golden

import java.io._

import tensil.tools.compiler.{DataMoveFlags, MemoryAddressRaw}
import tensil.tools.compiler.Opcode
import tensil.tools.{TraceContext}
import tensil.{Architecture, InstructionLayout}

abstract class Executive {
  def peekAccumulator(address: MemoryAddressRaw): Array[Float]
  def peekLocal(address: MemoryAddressRaw): Array[Float]
  def peekDRAM0(address: MemoryAddressRaw): Array[Float]
  def peekDRAM1(address: MemoryAddressRaw): Array[Float]

  def execMatMul(
      flags: Int,
      localStride: Int,
      localAddress: MemoryAddressRaw,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddressRaw,
      size: MemoryAddressRaw
  ): Unit
  def execDataMove(
      flags: Int,
      localStride: Int,
      localAddress: MemoryAddressRaw,
      accumulatorOrDRAMStride: Int,
      accumulatorOrDRAMAddress: MemoryAddressRaw,
      size: MemoryAddressRaw
  ): Unit
  def execLoadWeights(
      flags: Int,
      localStride: Int,
      localAddress: MemoryAddressRaw,
      size: MemoryAddressRaw
  ): Unit
  def execSIMD(
      flags: Int,
      simdOp: Int,
      simdSourceLeft: Int,
      simdSourceRight: Int,
      simdDestination: Int,
      writeAccumulatorAddress: MemoryAddressRaw,
      readAccumulatorAddress: MemoryAddressRaw
  ): Unit
}

class Decoder(arch: Architecture) {
  val layout = new InstructionLayout(arch)

  def decode(
      stream: InputStream,
      executive: Executive,
      trace: ExecutiveTrace
  ): Unit = {
    val bytes             = Array.fill[Byte](layout.instructionSizeBytes + 1)(0);
    var instructionOffset = 0L

    while (stream.read(bytes, 0, layout.instructionSizeBytes) != -1) {
      trace.runTrace(instructionOffset, executive)

      val instruction      = BigInt(bytes.reverse)
      var decodedSize: Int = 0

      def decodeInstructionBits(bitsSize: Int) = {
        val bits =
          (instruction >> decodedSize) & ((BigInt(1) << bitsSize) - BigInt(1))
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

      val header = instruction >> layout.operandsSizeBits

      val opcode: Int =
        (header >> (layout.flagsSizeBits + layout.tidSizeBits)).toByte
      val tid: Int =
        ((header >> layout.flagsSizeBits) & ((1 << layout.tidSizeBits) - 1)).toByte
      val flags: Int = (header & ((1 << layout.flagsSizeBits) - 1)).toByte

      if (opcode == Opcode.Wait) {
        skipBits(layout.operandsSizeBits)
      } else if (opcode == Opcode.MatMul) {
        val (localAddress, localStride)             = decodeAddressOperand0()
        val (accumulatorAddress, accumulatorStride) = decodeAddressOperand1()
        val size                                    = decodeSizeOperand2()

        executive.execMatMul(
          flags,
          localStride,
          localAddress,
          accumulatorStride,
          accumulatorAddress,
          size
        )
      } else if (opcode == Opcode.DataMove) {

        val (localAddress, localStride) = decodeAddressOperand0()
        val (accumulatorOrDRAMAddress, accumulatorOrDRAMStride) =
          decodeAddressOperand1()
        val size = decodeSizeOperand2()

        executive.execDataMove(
          flags,
          localStride,
          localAddress,
          accumulatorOrDRAMStride,
          accumulatorOrDRAMAddress,
          size
        )
      } else if (opcode == Opcode.LoadWeights) {

        val (localAddress, localStride) = decodeAddressOperand0()
        val size                        = decodeSizeOperand1()
        skipBits(layout.operand2SizeBits)

        executive.execLoadWeights(flags, localStride, localAddress, size)
      } else if (opcode == Opcode.SIMD) {

        val (writeAccumulatorAddress, _) = decodeAddressOperand0()
        val (readAccumulatorAddress, _)  = decodeAddressOperand1()
        val (simdDestination, simdSourceRight, simdSourceLeft, simdOp) =
          decodeSimdInstructionOperand2()

        executive.execSIMD(
          flags,
          simdOp,
          simdSourceLeft,
          simdSourceRight,
          simdDestination,
          writeAccumulatorAddress,
          readAccumulatorAddress
        )
      }

      require(decodedSize == layout.operandsSizeBits)

      instructionOffset += 1
    }

    trace.runTrace(instructionOffset, executive)
  }
}
