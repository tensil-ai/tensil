/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io._
import scala.collection.mutable
import tensil.{
  ArchitectureDataType,
  Architecture,
  TablePrinter,
  TableLine,
  InstructionLayout
}
import tensil.tools.util.divCeil
import tensil.tools.{CompilerException, TracepointCondition, TracepointsMap}

case class StrideStats(
    count: Long = 0,
    totalSize: Long = 0,
    maxSize: Long = 0
) {}

case class InstructionStats(
    count: Int = 0,
    totalSize: Long = 0,
    cycles: Long = 0,
    energy: Long = 0
) {}

object BackendStats {
  def getUnitsLetterAndDivisor(v: Long): (String, Float) =
    if (v < 1000L)
      ("", 1f)
    else if (v < 1000000L)
      ("K", 1e3f)
    else if (v < 1000000000L)
      ("M", 1e6f)
    else if (v < 1000000000000L)
      ("G", 1e9f)
    else
      ("T", 1e12f)

  def macEfficiency(
      stats: BackendStats,
      arch: Architecture,
      macs: Long
  ): Float =
    (macs.toFloat / (arch.arraySize * arch.arraySize).toFloat / stats.totalCycles.toFloat)

  def printSummary(
      stats: BackendStats,
      tb: TablePrinter,
      arch: Architecture,
      macs: Option[Long] = None
  ) = {
    val (cyclesLetter, cyclesDivisor) = getUnitsLetterAndDivisor(
      stats.totalCycles
    )
    val (energyLetter, energyDivisor) = getUnitsLetterAndDivisor(
      stats.totalEnergy
    )

    tb.addNamedLine(
      s"Latency (${cyclesLetter}Cycles)",
      stats.totalCycles.toFloat / cyclesDivisor
    )
    tb.addNamedLine(
      s"Energy (${energyLetter}Units)",
      stats.totalEnergy.toFloat / energyDivisor
    )

    if (macs.isDefined)
      tb.addNamedLine(
        "MAC efficiency (%)",
        macEfficiency(stats, arch, macs.get) * 100f
      )
  }

  def printCompositionSummary(name: String, stats: BackendStats) {
    val tb = new TablePrinter(
      Some(s"$name INSTRUCTIONS COMPOSITION (NUMBER/TOTAL SIZE)")
    )

    stats.instructionCounts.foreach({
      case (mnemonic, stats) =>
        tb.addNamedLine(
          mnemonic,
          stats.count,
          stats.totalSize
        )
    })

    tb.addNamedLine(
      "TOTAL",
      stats.instructionCounts.map(_._2.count).sum,
      stats.instructionCounts.map(_._2.totalSize).sum
    )

    print(tb)
  }

  def printCyclesSummary(name: String, stats: BackendStats) {
    val tb = new TablePrinter(
      Some(s"$name INSTRUCTIONS LATENCY")
    )

    stats.instructionCounts.foreach({
      case (mnemonic, stats) =>
        val (letter, divisor) = getUnitsLetterAndDivisor(stats.cycles)

        tb.addNamedLine(
          mnemonic,
          stats.cycles.toFloat / divisor,
          s"${letter}Cycles"
        )
    })

    val cycles            = stats.instructionCounts.map(_._2.cycles).sum
    val (letter, divisor) = getUnitsLetterAndDivisor(cycles)

    tb.addNamedLine(
      "TOTAL",
      cycles.toFloat / divisor,
      s"${letter}Cycles"
    )

    print(tb)
  }

  def printEnergySummary(name: String, stats: BackendStats) {
    val tb = new TablePrinter(
      Some(s"$name INSTRUCTIONS ENERGY")
    )

    stats.instructionCounts.foreach({
      case (mnemonic, stats) =>
        val (letter, divisor) = getUnitsLetterAndDivisor(stats.energy)

        tb.addNamedLine(
          mnemonic,
          stats.energy.toFloat / divisor,
          s"${letter}Units"
        )
    })

    val energy            = stats.instructionCounts.map(_._2.energy).sum
    val (letter, divisor) = getUnitsLetterAndDivisor(energy)

    tb.addNamedLine(
      "TOTAL",
      energy.toFloat / divisor,
      s"${letter}Units"
    )

    print(tb)
  }

  def printStrideStats(
      stride0Depth: Int,
      stride1Depth: Int,
      stats: BackendStats,
      select: StrideStats => Any,
      tb: TablePrinter
  ) = {
    val CellWidth = 10

    tb.addLine(
      new TableLine(
        ("Stride:") +: (0 until Math.max(stride0Depth, stride1Depth))
          .map(1 << _)
          .map(i => {
            val s = i.toString()
            s + (" " * (CellWidth - s.size))
          })
      )
    )

    for (
      (mnemonic, mnemonicStats) <-
        stats.strideStats.toSeq.sortBy({ case (mnemonic, _) => mnemonic });
      (operand, operandStats) <-
        mnemonicStats.toSeq.sortBy({ case (operand, _) => operand })
    ) {

      val depth = if (operand == 0) stride0Depth else stride1Depth
      val cells = for (i <- 0 until depth) yield {
        operandStats.get(i) match {
          case Some(stats) => select(stats)
          case None        => ""
        }
      }

      tb.addLine(new TableLine((s"$mnemonic#$operand:") +: cells))
    }
  }
}

class BackendStats {
  private val currentInstructionStats =
    mutable.Map.empty[String, InstructionStats]
  private val currentStrideStats =
    mutable.Map.empty[String, mutable.Map[Int, mutable.Map[Int, StrideStats]]]

  def instructionCounts = currentInstructionStats.toMap
  def strideStats =
    currentStrideStats.mapValues(_.mapValues(_.toMap).toMap).toMap

  def totalCycles = currentInstructionStats.values.map(_.cycles).sum
  def totalEnergy = currentInstructionStats.values.map(_.energy).sum

  def countInstruction(
      mnemonic: String,
      cycles: Long,
      energy: Long,
      size: Option[Long] = None
  ) =
    doCountInstruction(
      mnemonic,
      1,
      cycles,
      energy,
      if (size.isDefined) size.get + 1 else 1
    )

  private def doCountInstruction(
      mnemonic: String,
      count: Int,
      cycles: Long,
      energy: Long,
      size: Long
  ) = {
    val stats =
      currentInstructionStats.getOrElse(mnemonic, InstructionStats())
    currentInstructionStats.update(
      mnemonic,
      InstructionStats(
        count = stats.count + count,
        cycles = stats.cycles + cycles,
        energy = stats.energy + energy,
        totalSize = stats.totalSize + size
      )
    )
  }

  def countStride(mnemonic: String, operand: Int, stride: Int, size: Long) =
    doCountStride(mnemonic, operand, stride, 1, size + 1, size + 1)

  private def doCountStride(
      mnemonic: String,
      operand: Int,
      stride: Int,
      count: Long,
      totalSize: Long,
      maxSize: Long
  ) = {
    val mnemonicStats = currentStrideStats.getOrElseUpdate(
      mnemonic,
      mutable.Map.empty[Int, mutable.Map[Int, StrideStats]]
    )
    val operandStats = mnemonicStats.getOrElseUpdate(
      operand,
      mutable.Map.empty[Int, StrideStats]
    )
    val strideStats = operandStats.getOrElse(stride, StrideStats(0, 0, 0))

    operandStats.update(
      stride,
      StrideStats(
        count = strideStats.count + count,
        totalSize = strideStats.totalSize + totalSize,
        maxSize = Math.max(strideStats.maxSize, maxSize)
      )
    )
  }

  def add(stats: BackendStats): Unit = {
    stats.instructionCounts.foreach({
      case (mnemonic, stats) =>
        doCountInstruction(
          mnemonic,
          stats.count,
          stats.cycles,
          stats.energy,
          stats.totalSize
        )
    })

    for (
      (mnemonic, mnemonicStats) <- stats.strideStats;
      (operand, operandStats)   <- mnemonicStats;
      (stride, strideStats)     <- operandStats
    )
      doCountStride(
        mnemonic,
        operand,
        stride,
        strideStats.count,
        strideStats.totalSize,
        strideStats.maxSize
      )
  }
}

class BackendStream(
    val name: String,
    layout: InstructionLayout,
    dataType: ArchitectureDataType,
    printProgram: Boolean,
    printComments: Boolean,
    stats: Option[BackendStats],
    tracepointConditions: Seq[TracepointCondition],
    tracepointResolveRefToObject: (MemoryRef) => Option[MemoryObject] = (ref) =>
      None
) {
  private var previousOpcode = Opcode.NoOp
  private var previousFlags  = 0

  private val file = File.createTempFile("stream_", ".tprog")
  private val binaryDataStream = new DataOutputStream(
    new FileOutputStream(file)
  )

  private var currentInstructionSizeBits: Int = 0
  private var currentInstruction: BigInt      = 0

  private var instructionOffset: InstructionAddress = InstructionAddress.Zero
  private val instructionTracepointsMapsBuffer =
    mutable.Map.empty[InstructionAddress, TracepointsMap]

  private val InternalTransferEnergy = 10
  private val InternalTransferCycles = 1

  private val DRAMTransferEnergy = 100

  private val DRAMTransferSetupReadCycles  = 32
  private val DRAMTransferSetupWriteCycles = 0
  private val DRAMTransferReadCycles       = 2
  private val DRAMTransferWriteCycles      = 1
  private val DRAMTransferWidthBits        = 128

  private def beforeEmit(
      currentOp: Int,
      flags: Int = 0
  ): Unit = {
    previousOpcode = currentOp
    previousFlags = flags
  }

  private def estimateCyclesAndEnergy(
      currentOp: Int,
      size: Option[MemoryAddressRaw] = None,
      flags: Int = 0
  ): (Long, Long) = {
    currentOp match {
      case Opcode.NoOp =>
        val cycles = 1
        val energy = 0

        (cycles, energy)
      case Opcode.MatMul => {
        val cycles =
          (if (previousOpcode == Opcode.MatMul)
             (size.get + 1)
           else if (previousOpcode == Opcode.LoadWeights)
             (size.get + 1 + layout.arch.arraySize)
           else
             (size.get + 1 + 2 * layout.arch.arraySize))

        val energy =
          (size.get + 1) * layout.arch.arraySize * layout.arch.arraySize

        (cycles, energy)
      }

      case Opcode.SIMD => {
        val cycles = 1
        val energy = layout.arch.arraySize

        (cycles, energy)
      }

      case Opcode.LoadWeights => {
        val cycles = (size.get + 1) * InternalTransferCycles
        val energy = (size.get + 1) * InternalTransferEnergy

        (cycles, energy)
      }

      case Opcode.DataMove =>
        if (
          flags == DataMoveFlags.LocalToDRAM0 ||
          flags == DataMoveFlags.LocalToDRAM1 ||
          flags == DataMoveFlags.DRAM0ToLocal ||
          flags == DataMoveFlags.DRAM1ToLocal
        ) {
          val transfersPerVector = divCeil(
            dataType.sizeBytes * layout.arch.arraySize * 8,
            DRAMTransferWidthBits
          )
          val isRead =
            flags == DataMoveFlags.DRAM0ToLocal || flags == DataMoveFlags.DRAM1ToLocal
          val transferCycles =
            if (isRead) DRAMTransferReadCycles
            else DRAMTransferWriteCycles
          val setupCycles =
            if (previousOpcode == Opcode.DataMove && previousFlags == flags) 0
            else if (isRead) DRAMTransferSetupReadCycles
            else DRAMTransferSetupWriteCycles

          val cycles =
            (size.get + 1) * transfersPerVector * transferCycles + setupCycles
          val energy = (size.get + 1) * transfersPerVector * DRAMTransferEnergy

          (cycles, energy)
        } else {
          val cycles = (size.get + 1) * InternalTransferCycles
          val energy = (size.get + 1) * InternalTransferEnergy

          (cycles, energy)
        }

      case _ =>
        (0, 0)
    }
  }

  var instructionLinesBuffer = mutable.ArrayBuffer.empty[TableLine]

  def instructionsCount          = instructionOffset
  def instructionTracepointsMaps = instructionTracepointsMapsBuffer.toMap
  def instructionLines           = instructionLinesBuffer.toSeq

  def closeAndWriteToOutputStream(
      outputStream: OutputStream,
      buffer: Array[Byte]
  ): Unit = {
    binaryDataStream.close()

    val inputStream  = new FileInputStream(file)
    def readBuffer() = inputStream.read(buffer, 0, buffer.size)

    var sizeRead = readBuffer()

    while (sizeRead != -1) {
      outputStream.write(buffer.slice(0, sizeRead))
      sizeRead = readBuffer()
    }

    inputStream.close()
    file.delete()
  }

  def emitNoOp(
      mkComment: (Seq[MemoryAddress]) => Option[String] = (_) => None
  ): Unit = {
    beforeEmit(Opcode.NoOp)
    emitHeader(Opcode.NoOp)

    if (printProgram)
      printOp(
        "NoOp",
        "",
        if (printComments) mkComment(Nil) else None,
        currentInstruction
      )

    if (stats.isDefined) {
      val (cycles, energy) = estimateCyclesAndEnergy(Opcode.NoOp)
      stats.get.countInstruction("NoOp", cycles, energy)
    }

    emitInstruction()
  }

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw,
      mkComment: (Seq[MemoryAddress]) => Option[String] = (_) => None
  ): Unit = {
    require(
      localAddress.tag == MemoryTag.Local || localAddress.tag == MemoryTag.Zeroes
    )
    require(accumulatorAddress.tag == MemoryTag.Accumulators)

    beforeEmit(Opcode.MatMul)

    val flags = (if (accumulate) MatMulFlags.Accumulate else 0) |
      (if (localAddress.tag == MemoryTag.Zeroes) MatMulFlags.Zeroes
       else 0)

    emitLocalStrideAddressOperand0(localStride, localAddress.raw)
    emitAccumulatorStrideAddressOperand1(
      accumulatorStride,
      accumulatorAddress.raw
    )
    emitLocalAndAccumulatorSizeOperand2(size)
    emitHeader(Opcode.MatMul, flags)

    val mnemonic = "MatMul"

    if (printProgram) {
      val suffix =
        if (accumulate)
          "(Acc)"
        else
          ""

      printOp(
        mnemonic + suffix,
        s"${formatAddress(localStride, localAddress)} ${formatAddress(accumulatorStride, accumulatorAddress)}${formatSize(size)}",
        if (printComments) mkComment(Seq(accumulatorAddress, localAddress))
        else None,
        currentInstruction
      )
    }

    if (stats.isDefined) {
      val (cycles, energy) = estimateCyclesAndEnergy(Opcode.MatMul, Some(size))
      stats.get.countInstruction(mnemonic, cycles, energy, Some(size))
      if (localAddress.tag != MemoryTag.Zeroes)
        stats.get.countStride(mnemonic, 0, localStride, size)
      stats.get.countStride(mnemonic, 1, accumulatorStride, size)
    }

    emitInstruction()
    emitTracepoints(accumulatorAddress, size, accumulatorStride)
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
      mkComment: (Seq[MemoryAddress]) => Option[String] = (_) => None
  ): Unit = {
    require(
      writeAccumulatorAddress.tag == MemoryTag.Accumulators || writeAccumulatorAddress.tag == MemoryTag.Invalid
    )
    require(
      readAccumulatorAddress.tag == MemoryTag.Accumulators || readAccumulatorAddress.tag == MemoryTag.Invalid
    )

    beforeEmit(Opcode.SIMD)

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
    emitHeader(Opcode.SIMD, flags)

    val mnemonic = "SIMD"

    if (printProgram) {
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

      val comment =
        if (printComments)
          mkComment(Seq(writeAccumulatorAddress, readAccumulatorAddress))
        else None

      if (
        readAccumulatorAddress.tag == MemoryTag.Accumulators && writeAccumulatorAddress.tag == MemoryTag.Accumulators && accumulate
      ) {
        printOp(
          mnemonic + "(RWA)",
          s"${subInstructionName} W${MemoryAddressHelper(writeAccumulatorAddress)} R${MemoryAddressHelper(readAccumulatorAddress)}",
          comment,
          currentInstruction
        )
      } else if (
        writeAccumulatorAddress.tag == MemoryTag.Accumulators && accumulate
      ) {
        printOp(
          mnemonic + "(WA)",
          s"${subInstructionName} W${MemoryAddressHelper(writeAccumulatorAddress)}",
          comment,
          currentInstruction
        )
      } else if (
        readAccumulatorAddress.tag == MemoryTag.Accumulators && writeAccumulatorAddress.tag == MemoryTag.Accumulators
      ) {
        printOp(
          mnemonic + "(RW)",
          s"${subInstructionName} W${MemoryAddressHelper(writeAccumulatorAddress)} R${MemoryAddressHelper(readAccumulatorAddress)}",
          comment,
          currentInstruction
        )
      } else if (writeAccumulatorAddress.tag == MemoryTag.Accumulators) {
        printOp(
          mnemonic + "(W)",
          s"${subInstructionName} W${MemoryAddressHelper(writeAccumulatorAddress)}",
          comment,
          currentInstruction
        )
      } else if (readAccumulatorAddress.tag == MemoryTag.Accumulators) {
        printOp(
          mnemonic + "(R)",
          s"${subInstructionName} R${MemoryAddressHelper(readAccumulatorAddress)}",
          comment,
          currentInstruction
        )
      } else
        printOp(
          mnemonic,
          subInstructionName,
          comment,
          currentInstruction
        )
    }

    if (stats.isDefined) {
      val (cycles, energy) = estimateCyclesAndEnergy(Opcode.SIMD)
      stats.get.countInstruction(mnemonic, cycles, energy)
    }

    emitInstruction()
    emitTracepoints(writeAccumulatorAddress, 0L, 1)
  }

  def emitDataMove(
      toLocal: Boolean,
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      stride: Int,
      address: MemoryAddress,
      size: MemoryAddressRaw,
      mkComment: (Seq[MemoryAddress]) => Option[String] = (_) => None
  ): Unit = {
    require(
      localAddress.tag == MemoryTag.Local
    )
    require(
      address.tag == MemoryTag.Accumulators || address.tag == MemoryTag.Vars || address.tag == MemoryTag.Consts
    )

    val flags =
      if (toLocal)
        address.tag match {
          case MemoryTag.Accumulators => DataMoveFlags.AccumulatorToLocal
          case MemoryTag.Vars         => DataMoveFlags.DRAM0ToLocal
          case MemoryTag.Consts       => DataMoveFlags.DRAM1ToLocal
        }
      else
        address.tag match {
          case MemoryTag.Accumulators =>
            if (accumulate) DataMoveFlags.LocalToAccumulatorAccumulate
            else DataMoveFlags.LocalToAccumulator
          case MemoryTag.Vars   => DataMoveFlags.LocalToDRAM0
          case MemoryTag.Consts => DataMoveFlags.LocalToDRAM1
        }

    beforeEmit(Opcode.DataMove, flags)

    emitLocalStrideAddressOperand0(localStride, localAddress.raw)

    address.tag match {
      case MemoryTag.Accumulators =>
        emitAccumulatorStrideAddressOperand1(stride, address.raw)
        emitLocalAndAccumulatorSizeOperand2(size)
      case MemoryTag.Vars =>
        emitDRAM0StrideAddressOperand1(stride, address.raw)
        emitLocalAndDRAM0SizeOperand2(size)
      case MemoryTag.Consts =>
        emitDRAM1StrideAddressOperand1(stride, address.raw)
        emitLocalAndDRAM1SizeOperand2(size)
    }

    emitHeader(Opcode.DataMove, flags)

    val mnemonic = "DataMove"

    if (printProgram) {
      val suffix =
        if (toLocal)
          "(<-)"
        else if (accumulate)
          "(->, Acc)"
        else "(->)"

      printOp(
        mnemonic + suffix,
        s"${formatAddress(localStride, localAddress)} ${formatAddress(stride, address)}${formatSize(size)}",
        if (printComments) mkComment(Seq(localAddress, address)) else None,
        currentInstruction
      )
    }

    if (stats.isDefined) {
      val (cycles, energy) =
        estimateCyclesAndEnergy(Opcode.DataMove, Some(size), flags)

      val suffix = flags match {
        case DataMoveFlags.LocalToDRAM0       => "(LocalToDRAM0)"
        case DataMoveFlags.LocalToDRAM1       => "(LocalToDRAM1)"
        case DataMoveFlags.DRAM0ToLocal       => "(DRAM0ToLocal)"
        case DataMoveFlags.DRAM1ToLocal       => "(DRAM1ToLocal)"
        case DataMoveFlags.AccumulatorToLocal => "(AccToLocal)"
        case DataMoveFlags.LocalToAccumulator |
            DataMoveFlags.LocalToAccumulatorAccumulate =>
          "(LocalToAcc)"
      }
      val mnemonicWithSuffix = mnemonic + suffix

      stats.get.countInstruction(mnemonicWithSuffix, cycles, energy, Some(size))
      stats.get.countStride(mnemonicWithSuffix, 0, localStride, size)
      stats.get.countStride(mnemonicWithSuffix, 1, stride, size)
    }

    emitInstruction()

    if (toLocal)
      emitTracepoints(localAddress, size, localStride)
    else
      emitTracepoints(address, size, stride)
  }

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw,
      mkComment: (Seq[MemoryAddress]) => Option[String] = (_) => None
  ): Unit = {
    require(
      localAddress.tag == MemoryTag.Local || localAddress.tag == MemoryTag.Zeroes
    )

    beforeEmit(Opcode.LoadWeights)

    val flags = localAddress.tag match {
      case MemoryTag.Local  => LoadWeightsFlags.None
      case MemoryTag.Zeroes => LoadWeightsFlags.Zeroes
    }

    emitLocalStrideAddressOperand0(localStride, localAddress.raw)
    emitLocalSizeOperand1(size)
    emitHeader(Opcode.LoadWeights, flags)

    val mnemonic = "LoadWeights"

    if (printProgram) {
      printOp(
        mnemonic,
        s"${formatAddress(localStride, localAddress)}${formatSize(size)}",
        if (printComments) mkComment(Seq(localAddress)) else None,
        currentInstruction
      )
    }

    if (stats.isDefined) {
      val (cycles, energy) =
        estimateCyclesAndEnergy(Opcode.LoadWeights, Some(size))

      stats.get.countInstruction(mnemonic, cycles, energy, Some(size))

      if (localAddress.tag != MemoryTag.Zeroes)
        stats.get.countStride(mnemonic, 0, localStride, size)
    }

    emitInstruction()
  }

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

  private def emitHeader(opcode: Int, flags: Int = 0): Unit = {
    require(opcode >= 0 && flags >= 0)

    if (opcode >= 16)
      throw new CompilerException("Opcode overflow")

    if (flags >= 16)
      throw new CompilerException("Flags overflow")

    currentInstruction |= (BigInt(
      (opcode << 4) | flags
    ) << layout.operandsSizeBits)
  }

  private def emitInstructionBits(bits: Long, bitsSize: Int): Unit = {
    require(bits >= 0)

    currentInstruction |= (BigInt(bits) << currentInstructionSizeBits)
    currentInstructionSizeBits += bitsSize
  }

  private def mkInstructionBytes(): Array[Byte] = {
    val bytes            = currentInstruction.toByteArray
    val paddingBytesSize = layout.instructionSizeBytes - bytes.size

    if (paddingBytesSize < 0)
      throw new CompilerException("Operands overflow")

    val paddedBytes = Array.fill[Byte](paddingBytesSize)(0) ++ bytes

    paddedBytes.reverse
  }

  private def emitInstruction(): Unit = {
    val bytes = mkInstructionBytes()

    binaryDataStream.write(bytes, 0, bytes.size)
    currentInstruction = 0
    currentInstructionSizeBits = 0
    instructionOffset += 1
  }

  private def printOp(
      mnemonic: String,
      operands: String,
      comment: Option[String],
      instruction: BigInt
  ): Unit = {
    val bytes     = mkInstructionBytes()
    val hexBuffer = new StringBuffer()

    for (byte <- bytes) {
      val s = (0xff & byte.toInt).toHexString
      hexBuffer.append(if (s.size == 1) s"0x0$s," else s"0x$s,")
    }

    val comments = if (comment.isDefined) {
      val parts = comment.get.split(",").toIndexedSeq
      if (parts.size == 1)
        IndexedSeq(s"; ${parts(0)}")
      else
        s"; ${parts(0)}," +: parts
          .slice(1, parts.size - 1)
          .map(c => s";     ${c},") :+ s";     ${parts.last}"
    } else Nil

    instructionLinesBuffer += TableLine(
      hexBuffer,
      mnemonic,
      operands,
      comments
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

  private def emitTracepoints(
      address: MemoryAddress,
      size: MemoryAddressRaw,
      stride: Int
  ) {
    val tracepointsWriter =
      new TracepointsWriter(tracepointConditions, tracepointResolveRefToObject)
    val step = 1 << stride

    for (i <- 0L until size + 1) {
      tracepointsWriter.emitWrite(
        MemoryAddress(
          tag = address.tag,
          ref = address.ref,
          raw = address.raw + (i * step)
        )
      )

      val tracepointsMap = tracepointsWriter.toMap()

      if (!tracepointsMap.isEmpty)
        instructionTracepointsMapsBuffer(instructionOffset) = tracepointsMap
    }
  }
}
