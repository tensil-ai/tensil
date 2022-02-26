package tf2rtl.tools.compiler

import chisel3.util.log2Ceil
import tf2rtl.Architecture
import tf2rtl.tcu.simd

case class InstructionLayout(
    arch: Architecture
) {
  private val headerSizeBits = roundSizeBits(8)

  val localOperandSizeBits       = log2Ceil(arch.localDepth)
  val dram0OperandSizeBits       = log2Ceil(arch.dram0Depth)
  val dram1OperandSizeBits       = log2Ceil(arch.dram1Depth)
  val accumulatorOperandSizeBits = log2Ceil(arch.accumulatorDepth)

  val stride0SizeBits = log2Ceil(arch.stride0Depth)
  val stride1SizeBits = log2Ceil(arch.stride1Depth)

  val simdOpSizeBits          = log2Ceil(simd.Op.numOps)
  val simdOperandSizeBits     = log2Ceil(arch.simdRegistersDepth + 1)
  val simdInstructionSizeBits = simdOperandSizeBits * 3 + simdOpSizeBits

  def roundSizeBits(size: Int): Int = {
    val remainder = size % 8;
    if (remainder == 0)
      size
    else
      size + 8 - remainder
  }

  override def toString(): String = {
    val o1  = operand2SizeBits + 8
    val o0  = operand1SizeBits + o1
    val end = operand0SizeBits + o0
    s"""InstructionLayout ($instructionSizeBytes bytes):
      \t0:4   = opcode
      \t4:8   = flags
      \t8:$o1  = operand2 [$operand2Padding, $operand2AddressSizeBits]
      \t$o1:$o0 = operand1 [$operand1Padding, $stride1SizeBits, $operand1AddressSizeBits]
      \t$o0:$end = operand0 [$operand0Padding, $stride0SizeBits, $operand0AddressSizeBits]
      """.stripMargin
  }

  val operand0AddressSizeBits = List(
    localOperandSizeBits,      // MatMul, DataMove, LoadWeights
    accumulatorOperandSizeBits // SIMD
  ).max

  val operand0SizeBits = roundSizeBits(
    operand0AddressSizeBits + stride0SizeBits
  )

  val operand0Padding =
    operand0SizeBits - (operand0AddressSizeBits + stride0SizeBits)

  val operand1AddressSizeBits = List(
    localOperandSizeBits,      // LoadWeights
    dram0OperandSizeBits,      // DataMove
    dram1OperandSizeBits,      // DataMove
    accumulatorOperandSizeBits // MatMul, DataMove, SIMD
  ).max

  val operand1SizeBits =
    roundSizeBits(
      operand1AddressSizeBits + stride1SizeBits
    )

  val operand1Padding =
    operand1SizeBits - (operand1AddressSizeBits + stride1SizeBits)

  val operand2AddressSizeBits = List(
    List(
      localOperandSizeBits,
      accumulatorOperandSizeBits
    ).min,                                                // MatMul, DataMove
    List(localOperandSizeBits, dram0OperandSizeBits).min, // DataMove
    List(localOperandSizeBits, dram1OperandSizeBits).min, // DataMove
    simdInstructionSizeBits                               // SIMD
  ).max

  val operand2SizeBits =
    roundSizeBits(
      operand2AddressSizeBits
    )

  val operand2Padding = operand2SizeBits - operand2AddressSizeBits

  val operandsSizeBits = operand0SizeBits + operand1SizeBits + operand2SizeBits

  val instructionSizeBytes =
    (headerSizeBits + operandsSizeBits) / 8
}
