package tensil.tools.compiler

import tensil.InstructionLayout

class LIREstimator(layout: InstructionLayout, stats: Stats) extends LIR {
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
      case Opcode.Wait =>
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

  def emitNoOp(): Unit = {
    beforeEmit(Opcode.Wait)
  }

  def emitWait(tidToWait: Int): Unit = {
    beforeEmit(Opcode.Wait)

    val (cycles, energy) = estimateCyclesAndEnergy(Opcode.Wait)
    stats.countInstruction("Wait", cycles, energy)
  }

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit = {
    beforeEmit(Opcode.MatMul)

    val mnemonic = "MatMul"

    val (cycles, energy) = estimateCyclesAndEnergy(Opcode.MatMul, Some(size))
    stats.countInstruction(mnemonic, cycles, energy, Some(size))
    if (localAddress.tag != MemoryTag.Zeroes)
      stats.countStride(mnemonic, 0, localStride, size)
    stats.countStride(mnemonic, 1, accumulatorStride, size)
  }

  def emitSIMD(
      accumulate: Boolean,
      simdOp: Int,
      simdSourceLeft: Int,
      simdSourceRight: Int,
      simdDestination: Int,
      writeAccumulatorAddress: MemoryAddress,
      readAccumulatorAddress: MemoryAddress
  ): Unit = {
    beforeEmit(Opcode.SIMD)

    val (cycles, energy) = estimateCyclesAndEnergy(Opcode.SIMD)
    stats.countInstruction("SIMD", cycles, energy)
  }

  def emitDataMove(
      toLocal: Boolean,
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      stride: Int,
      address: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit = {
    val flags = LIRGen.mkDataMoveFlags(toLocal, accumulate, address.tag)

    beforeEmit(Opcode.DataMove, flags)

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

    val mnemonicWithSuffix = "DataMove" + suffix

    stats.countInstruction(mnemonicWithSuffix, cycles, energy, Some(size))
    stats.countStride(mnemonicWithSuffix, 0, localStride, size)
    stats.countStride(mnemonicWithSuffix, 1, stride, size)
  }

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit = {
    beforeEmit(Opcode.LoadWeights)

    val (cycles, energy) =
      estimateCyclesAndEnergy(Opcode.LoadWeights, Some(size))

    val mnemonic = "LoadWeights"

    stats.countInstruction(mnemonic, cycles, energy, Some(size))

    if (localAddress.tag != MemoryTag.Zeroes)
      stats.countStride(mnemonic, 0, localStride, size)
  }
}
