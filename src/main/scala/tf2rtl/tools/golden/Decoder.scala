package tf2rtl.tools.golden

import java.io._

import tf2rtl.tools.compiler.{
  InstructionLayout,
  DataMoveFlags,
  MemoryAddressRaw
}
import tf2rtl.tcu.instruction.Opcode
import tf2rtl.tools.TraceContext
import tf2rtl.Architecture

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
    val bytes             = new Array[Byte](layout.instructionSizeBytes);
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

      val header: Byte = (instruction >> layout.operandsSizeBits).toByte
      val opcode: Byte = (header >> 4).toByte
      val flags: Byte  = (header & 0xf).toByte

      if (opcode == Opcode.NoOp.litValue.toByte) {
        skipBits(layout.operandsSizeBits)
      } else if (opcode == Opcode.MatMul.litValue.toByte) {
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
      } else if (opcode == Opcode.DataMove.litValue.toByte) {

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
      } else if (opcode == Opcode.LoadWeights.litValue.toByte) {

        val (localAddress, localStride) = decodeAddressOperand0()
        val size                        = decodeSizeOperand1()
        skipBits(layout.operand2SizeBits)

        executive.execLoadWeights(flags, localStride, localAddress, size)
      } else if (opcode == Opcode.SIMD.litValue.toByte) {

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
