/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler.lir

import java.io.{FileOutputStream, DataOutputStream}

import tensil.tools.compiler.{
  LIR,
  InstructionContext,
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
  private val stream = new DataOutputStream(
    new FileOutputStream(printProgramFileName)
  )

  def emitWait(
      tidToWait: Int,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit =
    printOp(
      "Wait",
      s"$tidToWait",
      tid,
      context
    )

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
    val suffix =
      if (accumulate)
        "(Acc)"
      else
        ""

    printOp(
      s"MatMul${suffix}",
      s"${formatAddress(localStride, localAddress)} ${formatAddress(accumulatorStride, accumulatorAddress)}${formatSize(size)}",
      tid,
      context
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
        s"${mnemonic}(RWA)",
        s"${subInstructionName} W${MemoryAddressHelper(writeAccumulatorAddress)} R${MemoryAddressHelper(readAccumulatorAddress)}",
        tid,
        context
      )
    } else if (
      writeAccumulatorAddress.tag == MemoryTag.Accumulators && accumulate
    ) {
      printOp(
        s"${mnemonic}(WA)",
        s"${subInstructionName} W${MemoryAddressHelper(writeAccumulatorAddress)}",
        tid,
        context
      )
    } else if (
      readAccumulatorAddress.tag == MemoryTag.Accumulators && writeAccumulatorAddress.tag == MemoryTag.Accumulators
    ) {
      printOp(
        s"${mnemonic}(RW)",
        s"${subInstructionName} W${MemoryAddressHelper(writeAccumulatorAddress)} R${MemoryAddressHelper(readAccumulatorAddress)}",
        tid,
        context
      )
    } else if (writeAccumulatorAddress.tag == MemoryTag.Accumulators) {
      printOp(
        s"${mnemonic}(W)",
        s"${subInstructionName} W${MemoryAddressHelper(writeAccumulatorAddress)}",
        tid,
        context
      )
    } else if (readAccumulatorAddress.tag == MemoryTag.Accumulators) {
      printOp(
        s"${mnemonic}(R)",
        s"${subInstructionName} R${MemoryAddressHelper(readAccumulatorAddress)}",
        tid,
        context
      )
    } else
      printOp(
        mnemonic,
        subInstructionName,
        tid,
        context
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
      tid: Int,
      context: Option[InstructionContext]
  ): Unit = {
    val suffix =
      if (toLocal)
        "(<-)"
      else if (accumulate)
        "(->, Acc)"
      else "(->)"

    printOp(
      s"DataMove${suffix}",
      s"${formatAddress(localStride, localAddress)} ${formatAddress(stride, address)}${formatSize(size)}",
      tid,
      context
    )
  }

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit =
    printOp(
      "LoadWeights",
      s"${formatAddress(localStride, localAddress)}${formatSize(size)}",
      tid,
      context
    )

  def endEmit(): Unit = stream.close()

  private def printOp(
      mnemonic: String,
      operands: String,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit = {
    stream.writeBytes(
      s"[${context.get.address.get}] $tid $mnemonic $operands\r\n"
    )
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
