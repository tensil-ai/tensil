/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler.lir

import java.io.{OutputStream, DataOutputStream}

import tensil.InstructionLayout
import tensil.tools.CompilerException
import tensil.tools.compiler.{
  LIR,
  InstructionContext,
  MemoryAddress,
  MemoryAddressHelper,
  MemoryAddressRaw,
  MemoryTag,
  Opcode,
  DataMoveFlags,
  MatMulFlags,
  SIMDFlags,
  LoadWeightsFlags
}

object StreamGen {
  def mkDataMoveFlags(
      toLocal: Boolean,
      accumulate: Boolean,
      tag: MemoryTag
  ) =
    if (toLocal)
      tag match {
        case MemoryTag.Accumulators => DataMoveFlags.AccumulatorToLocal
        case MemoryTag.DRAM0         => DataMoveFlags.DRAM0ToLocal
        case MemoryTag.DRAM1       => DataMoveFlags.DRAM1ToLocal
      }
    else
      tag match {
        case MemoryTag.Accumulators =>
          if (accumulate) DataMoveFlags.LocalToAccumulatorAccumulate
          else DataMoveFlags.LocalToAccumulator
        case MemoryTag.DRAM0   => DataMoveFlags.LocalToDRAM0
        case MemoryTag.DRAM1 => DataMoveFlags.LocalToDRAM1
      }
}

class StreamGen(
    layout: InstructionLayout,
    stream: OutputStream,
    closeAtEndEmit: Boolean = false
) extends LIR {
  private var currentInstructionSizeBits: Int = 0
  private var currentInstruction: BigInt      = 0

  private val binaryDataStream = new DataOutputStream(stream)

  def emitWait(
      tidToWait: Int,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit = {
    emitTidOperand0(tidToWait)
    emitHeader(tid, Opcode.Wait)
    emitInstruction()
  }

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit = {
    require(
      localAddress.tag == MemoryTag.Local || localAddress.tag == MemoryTag.Zeroes
    )
    require(accumulatorAddress.tag == MemoryTag.Accumulators)

    val flags = (if (accumulate) MatMulFlags.Accumulate else 0) |
      (if (localAddress.tag == MemoryTag.Zeroes) MatMulFlags.Zeroes
       else 0)

    emitLocalStrideAddressOperand0(localStride, localAddress.raw)
    emitAccumulatorStrideAddressOperand1(
      accumulatorStride,
      accumulatorAddress.raw
    )
    emitLocalAndAccumulatorSizeOperand2(size)
    emitHeader(tid, Opcode.MatMul, flags)
    emitInstruction()
  }

  private def emitSimdInstructionOperand(
      simdOp: Int,
      simdSourceLeft: Int,
      simdSourceRight: Int,
      simdDestination: Int,
  ): Unit = {
    emitInstructionBits(simdDestination, layout.simdOperandSizeBits)
    emitInstructionBits(simdSourceRight, layout.simdOperandSizeBits)
    emitInstructionBits(simdSourceLeft, layout.simdOperandSizeBits)
    emitInstructionBits(simdOp, layout.simdOpSizeBits)
    emitInstructionBits(
      0L,
      layout.operand2SizeBits - layout.simdInstructionSizeBits
    )
  }

  def emitSIMD(
      accumulate: Boolean,
      simdOp: Int,
      simdSourceLeft: Int,
      simdSourceRight: Int,
      simdDestination: Int,
      writeAccumulatorAddress: MemoryAddress,
      readAccumulatorAddress: MemoryAddress,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit = {
    require(
      writeAccumulatorAddress.tag == MemoryTag.Accumulators || writeAccumulatorAddress.tag == MemoryTag.Invalid
    )
    require(
      readAccumulatorAddress.tag == MemoryTag.Accumulators || readAccumulatorAddress.tag == MemoryTag.Invalid
    )

    val flags =
      (if (readAccumulatorAddress.tag == MemoryTag.Accumulators)
         SIMDFlags.Read
       else 0) |
        (if (writeAccumulatorAddress.tag == MemoryTag.Accumulators)
           SIMDFlags.Write
         else 0) |
        (if (accumulate) SIMDFlags.Accumulate else 0)

    emitAccumulatorStrideAddressOperand0(
      0,
      writeAccumulatorAddress.raw
    )
    emitAccumulatorStrideAddressOperand1(
      0,
      readAccumulatorAddress.raw
    )
    emitSimdInstructionOperand(
      simdOp,
      simdSourceLeft,
      simdSourceRight,
      simdDestination
    )
    emitHeader(tid, Opcode.SIMD, flags)
    emitInstruction()
  }

  def emitDataMove(
      toLocal: Boolean,
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      stride: Int,
      address: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit = {
    require(
      localAddress.tag == MemoryTag.Local
    )
    require(
      address.tag == MemoryTag.Accumulators || address.tag == MemoryTag.DRAM0 || address.tag == MemoryTag.DRAM1
    )

    val flags = StreamGen.mkDataMoveFlags(toLocal, accumulate, address.tag)

    emitLocalStrideAddressOperand0(localStride, localAddress.raw)

    address.tag match {
      case MemoryTag.Accumulators =>
        emitAccumulatorStrideAddressOperand1(stride, address.raw)
        emitLocalAndAccumulatorSizeOperand2(size)
      case MemoryTag.DRAM0 =>
        emitDRAM0StrideAddressOperand1(stride, address.raw)
        emitLocalAndDRAM0SizeOperand2(size)
      case MemoryTag.DRAM1 =>
        emitDRAM1StrideAddressOperand1(stride, address.raw)
        emitLocalAndDRAM1SizeOperand2(size)
    }

    emitHeader(tid, Opcode.DataMove, flags)
    emitInstruction()
  }

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit = {
    require(
      localAddress.tag == MemoryTag.Local || localAddress.tag == MemoryTag.Zeroes
    )

    val flags = localAddress.tag match {
      case MemoryTag.Local  => LoadWeightsFlags.None
      case MemoryTag.Zeroes => LoadWeightsFlags.Zeroes
    }

    emitLocalStrideAddressOperand0(localStride, localAddress.raw)
    emitLocalSizeOperand1(size)
    emitHeader(tid, Opcode.LoadWeights, flags)
    emitInstruction()
  }

  def endEmit(): Unit =
    if (closeAtEndEmit)
      stream.close()

  private def emitAccumulatorStrideAddressOperand0(
      stride: Int,
      address: MemoryAddressRaw
  ): Unit = {
    if (stride >= layout.arch.stride0Depth)
      throw new CompilerException(s"Stride depth overflow at ${stride}")

    if (address >= layout.arch.accumulatorDepth)
      throw new CompilerException(s"Accumulator depth overflow at ${address}")

    emitInstructionBits(address, layout.operand0AddressSizeBits)
    emitInstructionBits(stride, layout.stride0SizeBits)
    emitInstructionBits(
      0L,
      layout.operand0SizeBits - (layout.operand0AddressSizeBits + layout.stride0SizeBits)
    )
  }

  private def emitLocalStrideAddressOperand0(
      stride: Int,
      address: MemoryAddressRaw
  ): Unit = {
    if (stride >= layout.arch.stride0Depth)
      throw new CompilerException(s"Stride depth overflow at ${stride}")

    if (address >= layout.arch.localDepth)
      throw new CompilerException(s"Local depth overflow at ${address}")

    emitInstructionBits(address, layout.operand0AddressSizeBits)
    emitInstructionBits(stride, layout.stride0SizeBits)
    emitInstructionBits(
      0L,
      layout.operand0SizeBits - (layout.operand0AddressSizeBits + layout.stride0SizeBits)
    )
  }

  private def emitAccumulatorStrideAddressOperand1(
      stride: Int,
      address: MemoryAddressRaw
  ): Unit = {
    if (stride >= layout.arch.stride1Depth)
      throw new CompilerException(s"Stride depth overflow at ${stride}")

    if (address >= layout.arch.accumulatorDepth)
      throw new CompilerException(s"Accumulator depth overflow at ${address}")

    emitInstructionBits(address, layout.operand1AddressSizeBits)
    emitInstructionBits(stride, layout.stride1SizeBits)
    emitInstructionBits(
      0L,
      layout.operand1SizeBits - (layout.operand1AddressSizeBits + layout.stride1SizeBits)
    )
  }

  private def emitDRAM0StrideAddressOperand1(
      stride: Int,
      address: MemoryAddressRaw
  ): Unit = {
    if (stride >= layout.arch.stride1Depth)
      throw new CompilerException(s"Stride depth overflow at ${stride}")

    if (address >= layout.arch.dram0Depth)
      throw new CompilerException(s"DRAM0 depth overflow at ${address}")

    emitInstructionBits(address, layout.operand1AddressSizeBits)
    emitInstructionBits(stride, layout.stride1SizeBits)
    emitInstructionBits(
      0L,
      layout.operand1SizeBits - (layout.operand1AddressSizeBits + layout.stride1SizeBits)
    )
  }

  private def emitDRAM1StrideAddressOperand1(
      stride: Int,
      address: MemoryAddressRaw
  ): Unit = {
    if (stride >= layout.arch.stride1Depth)
      throw new CompilerException(s"Stride depth overflow at ${stride}")

    if (address >= layout.arch.dram1Depth)
      throw new CompilerException(s"DRAM1 depth overflow at ${address}")

    emitInstructionBits(address, layout.operand1AddressSizeBits)
    emitInstructionBits(stride, layout.stride1SizeBits)
    emitInstructionBits(
      0L,
      layout.operand1SizeBits - (layout.operand1AddressSizeBits + layout.stride1SizeBits)
    )
  }

  private def emitLocalSizeOperand1(size: MemoryAddressRaw): Unit = {
    if (size >= layout.arch.localDepth)
      throw new CompilerException(s"Local depth overflow at ${size}")

    emitInstructionBits(size, layout.operand1AddressSizeBits)
    emitInstructionBits(
      0L,
      layout.operand1SizeBits - layout.operand1AddressSizeBits
    )
  }

  private def emitLocalAndAccumulatorSizeOperand2(
      size: MemoryAddressRaw
  ): Unit = {
    if (size >= layout.arch.localDepth)
      throw new CompilerException(s"Local depth overflow at ${size}")

    if (size >= layout.arch.accumulatorDepth)
      throw new CompilerException(s"Accumulator depth overflow at ${size}")

    emitInstructionBits(size, layout.operand2AddressSizeBits)
    emitInstructionBits(
      0L,
      layout.operand2SizeBits - layout.operand2AddressSizeBits
    )
  }

  private def emitLocalAndDRAM0SizeOperand2(size: MemoryAddressRaw): Unit = {
    if (size >= layout.arch.localDepth)
      throw new CompilerException(s"Local depth overflow at ${size}")

    if (size >= layout.arch.dram0Depth)
      throw new CompilerException(s"DRAM0 depth overflow at ${size}")

    emitInstructionBits(size, layout.operand2AddressSizeBits)
    emitInstructionBits(
      0L,
      layout.operand2SizeBits - layout.operand2AddressSizeBits
    )
  }

  private def emitLocalAndDRAM1SizeOperand2(size: MemoryAddressRaw): Unit = {
    if (size >= layout.arch.localDepth)
      throw new CompilerException(s"Local depth overflow at ${size}")

    if (size >= layout.arch.dram1Depth)
      throw new CompilerException(s"DRAM1 depth overflow at ${size}")

    emitInstructionBits(size, layout.operand2AddressSizeBits)
    emitInstructionBits(
      0L,
      layout.operand2SizeBits - layout.operand2AddressSizeBits
    )
  }

  private def emitTidOperand0(
      tid: Int
  ): Unit = {
    if (tid >= (1 << layout.tidSizeBits))
      throw new CompilerException(s"TID overflow")

    emitInstructionBits(tid, layout.tidSizeBits)
    emitInstructionBits(
      0L,
      layout.operand0SizeBits - layout.tidSizeBits
    )
  }

  private def emitHeader(tid: Int, opcode: Int, flags: Int = 0): Unit = {
    require(tid >= 0 && opcode >= 0 && flags >= 0)

    if (tid >= (1 << layout.tidSizeBits))
      throw new CompilerException("TID overflow")

    if (opcode >= (1 << layout.opcodeSizeBits))
      throw new CompilerException("Opcode overflow")

    if (flags >= (1 << layout.flagsSizeBits))
      throw new CompilerException("Flags overflow")

    val header = BigInt(
      (tid << (layout.opcodeSizeBits + layout.flagsSizeBits)) | (opcode << layout.flagsSizeBits) | flags
    )

    currentInstruction |= (header << layout.operandsSizeBits)
  }

  private def emitInstructionBits(bits: Long, bitsSize: Int): Unit = {
    require(bits >= 0)

    currentInstruction |= (BigInt(bits) << currentInstructionSizeBits)
    currentInstructionSizeBits += bitsSize
  }

  private def mkInstructionBytes(): Array[Byte] = {
    val bytes            = currentInstruction.toByteArray.dropWhile(b => b == 0)
    val paddingBytesSize = layout.instructionSizeBytes - bytes.size

    if (paddingBytesSize < 0)
      throw new CompilerException(s"Operands overflow ${bytes.toList}")

    val paddedBytes = Array.fill[Byte](paddingBytesSize)(0) ++ bytes

    paddedBytes.reverse
  }

  private def emitInstruction(): Unit = {
    val bytes = mkInstructionBytes()

    binaryDataStream.write(bytes, 0, bytes.size)
    currentInstruction = 0
    currentInstructionSizeBits = 0
  }
}
