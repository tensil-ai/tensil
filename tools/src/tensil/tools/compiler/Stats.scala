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

object Stats {
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
      stats: Stats,
      arch: Architecture,
      macs: Long
  ): Float =
    (macs.toFloat / (arch.arraySize * arch.arraySize).toFloat / stats.totalCycles.toFloat)

  def printSummary(
      stats: Stats,
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

  def printCompositionSummary(name: String, stats: Stats) {
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

  def printCyclesSummary(name: String, stats: Stats) {
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

  def printEnergySummary(name: String, stats: Stats) {
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
      stats: Stats,
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

class Stats {
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
      estimate: Estimate,
      size: Option[Long] = None
  ) =
    doCountInstruction(
      mnemonic,
      1,
      estimate.cycles,
      estimate.energy,
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

  def add(stats: Stats): Unit = {
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
