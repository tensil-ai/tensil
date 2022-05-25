/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler.lir

import java.io.{FileOutputStream, DataOutputStream}

import tensil.tools.compiler.{
  LIR,
  MemoryAddress,
  MemoryAddressHelper,
  MemoryAddressRaw,
  MemoryTag,
  InstructionAddress,
  SIMDOp
}

class Printer(
    printProgramFileName: String
) extends LIR {
  private var instructionOffset: InstructionAddress = InstructionAddress.Zero
  private val stream = new DataOutputStream(
    new FileOutputStream(printProgramFileName)
  )

  def emitWait(tidToWait: Int, tid: Int): Unit =
    printOp(
      "Wait",
      s"$tidToWait",
      tid
    )

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int
  ): Unit = {
    val suffix =
      if (accumulate)
        "(Acc)"
      else
        ""

    printOp(
      "MatMul" + suffix,
      s"${formatAddress(localStride, localAddress)} ${formatAddress(accumulatorStride, accumulatorAddress)}${formatSize(size)}",
      tid
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
      tid: Int
  ): Unit = {
    val mnemonic = "SIMD"

    def formatSource(source: Int) =
      source match {
        case 0 => "I"
        case r => s"R$r"
      }

    def formatDestination(destination: Int) =
      destination match {
        case 0 => "O"
        case r => s"R$r"
      }

    val subInstructionName = simdOp match {
      case SIMDOp.Zero => s"${formatDestination(simdDestination)}=0"
      case SIMDOp.Max =>
        s"${formatDestination(simdDestination)}=Max(${formatSource(simdSourceLeft)},${formatSource(simdSourceRight)})"
      case SIMDOp.Add =>
        s"${formatDestination(simdDestination)}=Add(${formatSource(simdSourceLeft)},${formatSource(simdSourceRight)})"
      case SIMDOp.Multiply =>
        s"${formatDestination(simdDestination)}=Multiply(${formatSource(simdSourceLeft)},${formatSource(simdSourceRight)})"
      case SIMDOp.Move =>
        s"${formatDestination(simdDestination)}=${formatSource(simdSourceLeft)}"
    }

    if (
      readAccumulatorAddress.tag == MemoryTag.Accumulators && writeAccumulatorAddress.tag == MemoryTag.Accumulators && accumulate
    ) {
      printOp(
        mnemonic + "(RWA)",
        s"${subInstructionName} W${MemoryAddressHelper(writeAccumulatorAddress)} R${MemoryAddressHelper(readAccumulatorAddress)}",
        tid
      )
    } else if (
      writeAccumulatorAddress.tag == MemoryTag.Accumulators && accumulate
    ) {
      printOp(
        mnemonic + "(WA)",
        s"${subInstructionName} W${MemoryAddressHelper(writeAccumulatorAddress)}",
        tid
      )
    } else if (
      readAccumulatorAddress.tag == MemoryTag.Accumulators && writeAccumulatorAddress.tag == MemoryTag.Accumulators
    ) {
      printOp(
        mnemonic + "(RW)",
        s"${subInstructionName} W${MemoryAddressHelper(writeAccumulatorAddress)} R${MemoryAddressHelper(readAccumulatorAddress)}",
        tid
      )
    } else if (writeAccumulatorAddress.tag == MemoryTag.Accumulators) {
      printOp(
        mnemonic + "(W)",
        s"${subInstructionName} W${MemoryAddressHelper(writeAccumulatorAddress)}",
        tid
      )
    } else if (readAccumulatorAddress.tag == MemoryTag.Accumulators) {
      printOp(
        mnemonic + "(R)",
        s"${subInstructionName} R${MemoryAddressHelper(readAccumulatorAddress)}",
        tid
      )
    } else
      printOp(
        mnemonic,
        subInstructionName,
        tid
      )
  }

  def emitDataMove(
      toLocal: Boolean,
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      stride: Int,
      address: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int
  ): Unit = {
    val suffix =
      if (toLocal)
        "(<-)"
      else if (accumulate)
        "(->, Acc)"
      else "(->)"

    printOp(
      "DataMove" + suffix,
      s"${formatAddress(localStride, localAddress)} ${formatAddress(stride, address)}${formatSize(size)}",
      tid
    )
  }

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int
  ): Unit =
    printOp(
      "LoadWeights",
      s"${formatAddress(localStride, localAddress)}${formatSize(size)}",
      tid
    )

  def endEmit(): Unit = stream.close()

  private def printOp(
      mnemonic: String,
      operands: String,
      tid: Int
  ): Unit = {
    stream.writeBytes(s"[$instructionOffset] $tid $mnemonic $operands\r\n")

    instructionOffset += 1
  }

  private def formatAddress(stride: Int, address: MemoryAddress) =
    if (
      stride != 0 && address.tag != MemoryTag.Zeroes && address.tag != MemoryTag.Invalid
    )
      s"${MemoryAddressHelper(address)}@2^${stride}"
    else
      MemoryAddressHelper(address).toString()

  private def formatSize(size: MemoryAddressRaw) =
    if (size != 0) s" $size(+1)" else ""
}
