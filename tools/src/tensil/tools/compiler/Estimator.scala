/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import tensil.InstructionLayout

case class Estimate(cycles: Long, energy: Long)

class Estimator(layout: InstructionLayout) {
  private var previousOpcode = Opcode.Wait
  private var previousFlags  = 0

  private val InternalTransferEnergy = 10
  private val InternalTransferCycles = 1

  private val DRAMTransferEnergy = 100

  private val DRAMTransferSetupReadCycles  = 32
  private val DRAMTransferSetupWriteCycles = 0
  private val DRAMTransferReadCycles       = 2
  private val DRAMTransferWriteCycles      = 1
  private val DRAMTransferWidthBits        = 128

  def estimateCyclesAndEnergy(
      currentOp: Int,
      size: Option[MemoryAddressRaw] = None,
      flags: Int = 0
  ): Estimate = {
    val r = currentOp match {
      case Opcode.Wait =>
        val cycles = 1L
        val energy = 0L

        Estimate(cycles, energy)
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

        Estimate(cycles, energy)
      }

      case Opcode.SIMD => {
        val cycles = 1L
        val energy = layout.arch.arraySize.toLong

        Estimate(cycles, energy)
      }

      case Opcode.LoadWeights => {
        val cycles = (size.get + 1) * InternalTransferCycles
        val energy = (size.get + 1) * InternalTransferEnergy

        Estimate(cycles, energy)
      }

      case Opcode.DataMove =>
        if (
          flags == DataMoveFlags.LocalToDRAM0 ||
          flags == DataMoveFlags.LocalToDRAM1 ||
          flags == DataMoveFlags.DRAM0ToLocal ||
          flags == DataMoveFlags.DRAM1ToLocal
        ) {
          /*val transfersPerVector = divCeil(
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
          val energy = (size.get + 1) * transfersPerVector * DRAMTransferEnergy*/

          val cycles = (size.get + 1) * 1
          val energy = (size.get + 1) * 1

          Estimate(cycles, energy)
        } else {
          val cycles = (size.get + 1) * InternalTransferCycles
          val energy = (size.get + 1) * InternalTransferEnergy

          Estimate(cycles, energy)
        }

      case _ =>
        Estimate(0L, 0L)
    }

    previousOpcode = currentOp
    previousFlags = flags

    r
  }
}
