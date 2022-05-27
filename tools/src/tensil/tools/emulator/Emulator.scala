/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.emulator

import java.io._

import scala.Numeric.Implicits._
import scala.Ordering.Implicits._
import scala.reflect.ClassTag
import scala.collection.mutable

import tensil.tools.data.Tensor
import tensil.tools.{TraceContext}
import tensil.{
  Architecture,
  ArchitectureDataTypeWithBase,
  TablePrinter,
  emulator
}
import tensil.tools.compiler.{
  LoadWeightsFlags,
  SIMDFlags,
  SIMDOp,
  MatMulFlags,
  DataMoveFlags,
  SIMDSource,
  SIMDDestination,
  MemoryTag,
  MemoryAddressRaw,
  MemoryAddress,
  MemoryAddressHelper,
  LIR,
  InstructionContext,
  lir
}
import tensil.NumericWithMAC
import tensil.tools.compiler.InstructionAddress

class Emulator[T : NumericWithMAC : ClassTag](
    dataType: ArchitectureDataTypeWithBase[T],
    arch: Architecture
) {
  private val executive = new EmulatorExecutive(arch)

  private def arrayToStream(bytes: Array[Byte]): InputStream = {
    new ByteArrayInputStream(bytes)
  }

  def writeDRAM0(addresses: Seq[MemoryAddressRaw], stream: InputStream): Unit =
    executive.writeDRAM0(addresses, new DataInputStream(stream))
  def writeDRAM0(addresses: Seq[MemoryAddressRaw], bytes: Array[Byte]): Unit =
    writeDRAM0(addresses, arrayToStream(bytes))

  def readDRAM0(addresses: Seq[MemoryAddressRaw], stream: OutputStream): Unit =
    executive.readDRAM0(addresses, new DataOutputStream(stream))
  def readDRAM0(addresses: Seq[MemoryAddressRaw]): Array[Byte] = {
    val bytesStream = new ByteArrayOutputStream()
    executive.readDRAM0(addresses, new DataOutputStream(bytesStream))
    bytesStream.toByteArray()
  }

  def writeDRAM1(size: MemoryAddressRaw, stream: InputStream) =
    executive.writeDRAM1(0L until size, new DataInputStream(stream))
  def writeDRAM1(size: MemoryAddressRaw, bytes: Array[Byte]): Unit =
    writeDRAM1(size, arrayToStream(bytes))

  def run(programBytes: Array[Byte], trace: ExecutiveTrace): Unit =
    run(new ByteArrayInputStream(programBytes), trace)

  def run(stream: InputStream, trace: ExecutiveTrace): Unit = {
    val startTime = System.nanoTime()

    val traceLir = new LIR {

      /**
        * This will run the trace for inital tracepoints.
        * See MemoryManager.emitInitialTracepoints.
        */
      trace.runTrace(-InstructionAddress.One, executive)

      private def runTrace(context: Option[InstructionContext]): Unit =
        trace.runTrace(context.get.address.get, executive)

      def emitWait(
          tidToWait: Int,
          tid: Int,
          context: Option[InstructionContext]
      ): Unit =
        runTrace(context)

      def emitMatMul(
          accumulate: Boolean,
          localStride: Int,
          localAddress: MemoryAddress,
          accumulatorStride: Int,
          accumulatorAddress: MemoryAddress,
          size: MemoryAddressRaw,
          tid: Int,
          context: Option[InstructionContext]
      ): Unit = runTrace(context)

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
      ): Unit = runTrace(context)

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
      ): Unit = runTrace(context)

      def emitLoadWeights(
          localStride: Int,
          localAddress: MemoryAddress,
          size: MemoryAddressRaw,
          tid: Int,
          context: Option[InstructionContext]
      ): Unit = runTrace(context)

      def endEmit(): Unit = {}
    }

    val parser =
      lir.Parser.injectInstructionAddress(new lir.StreamParser(arch, stream))

    val sequencerLir = new lir.Sequencer(
      arch = arch,
      readLir = executive.readLir,
      writeLir = new lir.Broadcast(
        executive.writeLir,
        traceLir
      )
    )

    try {
      parser.parseAll(sequencerLir)
    } finally {
      val endTime = System.nanoTime()

      val tb = new TablePrinter(Some("EMULATOR SUMMARY"))
      tb.addNamedLine(
        "Execution time (sec)",
        (endTime - startTime).toFloat / 1e9f
      )
      print(tb)

      dataType.reportAndResetOverUnderflowStats()
    }
  }

  private class EmulatorExecutive(arch: Architecture) extends Executive {
    val zero = implicitly[Numeric[T]].zero
    val one  = implicitly[Numeric[T]].one

    val localArray       = mkArray(arch.arraySize * arch.localDepth.toInt)
    val dram0Array       = mkArray(arch.arraySize * arch.dram0Depth.toInt)
    val dram1Array       = mkArray(arch.arraySize * arch.dram1Depth.toInt)
    val accumulatorArray = mkArray(arch.arraySize * arch.accumulatorDepth.toInt)
    val simdRegistersArray = mkArray(
      arch.arraySize * arch.simdRegistersDepth.toInt
    )

    val localReads       = mutable.Map.empty[Int, Array[T]]
    val dram0Reads       = mutable.Map.empty[Int, Array[T]]
    val dram1Reads       = mutable.Map.empty[Int, Array[T]]
    val accumulatorReads = mutable.Map.empty[Int, Array[T]]

    var currentWeights =
      Array.fill(arch.arraySize + 1, arch.arraySize)(zero)

    def peekAccumulator(address: MemoryAddressRaw): Array[Float] = {
      accumulatorArray
        .slice(
          address.toInt * arch.arraySize,
          (address.toInt + 1) * arch.arraySize
        )
        .map(_.toFloat)
        .toArray
    }

    def peekLocal(address: MemoryAddressRaw): Array[Float] = {
      localArray
        .slice(
          address.toInt * arch.arraySize,
          (address.toInt + 1) * arch.arraySize
        )
        .map(_.toFloat)
        .toArray
    }

    def peekDRAM0(address: MemoryAddressRaw): Array[Float] = {
      dram0Array
        .slice(
          address.toInt * arch.arraySize,
          (address.toInt + 1) * arch.arraySize
        )
        .map(_.toFloat)
        .toArray
    }

    def peekDRAM1(address: MemoryAddressRaw): Array[Float] = {
      dram1Array
        .slice(
          address.toInt * arch.arraySize,
          (address.toInt + 1) * arch.arraySize
        )
        .map(_.toFloat)
        .toArray
    }

    def writeDRAM0(
        addresses: Seq[MemoryAddressRaw],
        stream: DataInputStream
    ): Unit = {
      for (i <- addresses)
        for (j <- 0 until arch.arraySize)
          dram0Array(i.toInt * arch.arraySize + j) = dataType.readConst(stream)
    }
    def readDRAM0(
        addresses: Seq[MemoryAddressRaw],
        stream: DataOutputStream
    ): Unit = {
      for (i <- addresses)
        for (j <- 0 until arch.arraySize)
          dataType.writeConst(dram0Array(i.toInt * arch.arraySize + j), stream)
    }
    def writeDRAM1(
        addresses: Seq[MemoryAddressRaw],
        stream: DataInputStream
    ): Unit = {
      for (i <- addresses)
        for (j <- 0 until arch.arraySize)
          dram1Array(i.toInt * arch.arraySize + j) = dataType.readConst(stream)
    }

    private def executeRead(
        array: Array[T],
        reads: mutable.Map[Int, Array[T]],
        offset: Int
    ): Unit = {
      require(!reads.isDefinedAt(offset))
      reads(offset) = array.slice(offset, offset + arch.arraySize)
    }

    private def executeReadLocal(offset: Int) =
      executeRead(localArray, localReads, offset)
    private def executeReadDram0(offset: Int) =
      executeRead(dram0Array, dram0Reads, offset)
    private def executeReadDram1(offset: Int) =
      executeRead(dram1Array, dram1Reads, offset)
    private def executeReadAccumulator(offset: Int) =
      executeRead(accumulatorArray, accumulatorReads, offset)

    private def finishRead(
        array: Array[T],
        reads: mutable.Map[Int, Array[T]],
        offset: Int
    ): Array[T] = {
      reads.remove(offset).get
    }

    private def finishReadLocal(offset: Int) =
      finishRead(localArray, localReads, offset)
    private def finishReadDram0(offset: Int) =
      finishRead(dram0Array, dram0Reads, offset)
    private def finishReadDram1(offset: Int) =
      finishRead(dram1Array, dram1Reads, offset)
    private def finishReadAccumulator(offset: Int) =
      finishRead(accumulatorArray, accumulatorReads, offset)

    val readLir = new LIR {
      def emitWait(
          tidToWait: Int,
          tid: Int,
          context: Option[InstructionContext]
      ): Unit = {}

      def emitMatMul(
          accumulate: Boolean,
          localStride: Int,
          localAddress: MemoryAddress,
          accumulatorStride: Int,
          accumulatorAddress: MemoryAddress,
          size: MemoryAddressRaw,
          tid: Int,
          context: Option[InstructionContext]
      ): Unit =
        if (localAddress.tag != MemoryTag.Zeroes) {
          val localBase = localAddress.raw.toInt * arch.arraySize.toInt
          val localStep = 1 << localStride

          for (i <- 0 to size.toInt)
            executeReadLocal(localBase + i * arch.arraySize * localStep)
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
      ): Unit =
        if (
          (simdSourceLeft == SIMDSource.Input || simdSourceRight == SIMDSource.Input) && readAccumulatorAddress.tag == MemoryTag.Accumulators
        )
          executeReadAccumulator(
            readAccumulatorAddress.raw.toInt * arch.arraySize.toInt
          )

      def emitDataMove(
          toLocal: Boolean,
          accumulate: Boolean,
          localStride: Int,
          localAddress: MemoryAddress,
          accumulatorOrDRAMStride: Int,
          accumulatorOrDRAMAddress: MemoryAddress,
          size: MemoryAddressRaw,
          tid: Int,
          context: Option[InstructionContext]
      ): Unit = {
        val localBase = localAddress.raw.toInt * arch.arraySize.toInt
        val accumulatorOrDRAMBase =
          accumulatorOrDRAMAddress.raw.toInt * arch.arraySize.toInt

        val localStep             = 1 << localStride
        val accumulatorOrDRAMStep = 1 << accumulatorOrDRAMStride

        for (i <- 0 to size.toInt)
          if (toLocal) {
            val k =
              accumulatorOrDRAMBase + i * arch.arraySize * accumulatorOrDRAMStep

            accumulatorOrDRAMAddress.tag match {
              case MemoryTag.Accumulators =>
                executeReadAccumulator(k)

              case MemoryTag.Vars =>
                executeReadDram0(k)

              case MemoryTag.Consts =>
                executeReadDram1(k)
            }
          } else
            executeReadLocal(localBase + i * arch.arraySize * localStep)
      }

      def emitLoadWeights(
          localStride: Int,
          localAddress: MemoryAddress,
          size: MemoryAddressRaw,
          tid: Int,
          context: Option[InstructionContext]
      ): Unit =
        if (localAddress.tag != MemoryTag.Zeroes) {
          val localBase = localAddress.raw.toInt * arch.arraySize.toInt
          val localStep = 1 << localStride

          for (i <- 0 to size.toInt)
            executeReadLocal(localBase + i * arch.arraySize * localStep)
        }

      override def endEmit(): Unit = {}
    }

    val writeLir = new LIR {
      def emitWait(
          tidToWait: Int,
          tid: Int,
          context: Option[InstructionContext]
      ): Unit = {}

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
        val localBase = localAddress.raw.toInt * arch.arraySize.toInt
        val accumulatorBase =
          accumulatorAddress.raw.toInt * arch.arraySize.toInt

        val localStep       = 1 << localStride
        val accumulatorStep = 1 << accumulatorStride

        for (i <- 0 to size.toInt) {
          val x =
            Array(
              Array[T](one) ++ (if (localAddress.tag == MemoryTag.Zeroes)
                                  Array.fill(arch.arraySize)(zero)
                                else
                                  finishReadLocal(
                                    localBase + i * arch.arraySize * localStep
                                  ))
            )

          val y = emulator.Ops.matMul(x, currentWeights)

          for (j <- 0 until arch.arraySize)
            if (accumulate)
              accumulatorArray(
                accumulatorBase + i * arch.arraySize * accumulatorStep + j
              ) += y(0)(j)
            else
              accumulatorArray(
                accumulatorBase + i * arch.arraySize * accumulatorStep + j
              ) = y(0)(j)
        }
      }

      def emitDataMove(
          toLocal: Boolean,
          accumulate: Boolean,
          localStride: Int,
          localAddress: MemoryAddress,
          accumulatorOrDRAMStride: Int,
          accumulatorOrDRAMAddress: MemoryAddress,
          size: MemoryAddressRaw,
          tid: Int,
          context: Option[InstructionContext]
      ): Unit = {
        val localBase = localAddress.raw.toInt * arch.arraySize.toInt
        val accumulatorOrDRAMBase =
          accumulatorOrDRAMAddress.raw.toInt * arch.arraySize.toInt

        val localStep             = 1 << localStride
        val accumulatorOrDRAMStep = 1 << accumulatorOrDRAMStride

        for (i <- 0 to size.toInt) {
          val x = if (toLocal) {
            val k =
              accumulatorOrDRAMBase + i * arch.arraySize * accumulatorOrDRAMStep

            accumulatorOrDRAMAddress.tag match {
              case MemoryTag.Accumulators =>
                finishReadAccumulator(k)

              case MemoryTag.Vars =>
                finishReadDram0(k)

              case MemoryTag.Consts =>
                finishReadDram1(k)
            }
          } else
            finishReadLocal(localBase + i * arch.arraySize * localStep)

          val (y, yBase) =
            if (toLocal)
              (localArray, localBase + i * arch.arraySize * localStep)
            else {
              val base =
                accumulatorOrDRAMBase + i * arch.arraySize * accumulatorOrDRAMStep

              accumulatorOrDRAMAddress.tag match {
                case MemoryTag.Accumulators =>
                  (accumulatorArray, base)

                case MemoryTag.Vars =>
                  (dram0Array, base)

                case MemoryTag.Consts =>
                  (dram1Array, base)
              }
            }

          for (j <- 0 until arch.arraySize)
            if (accumulate)
              y(yBase + j) += x(j)
            else
              y(yBase + j) = x(j)
        }
      }

      def emitLoadWeights(
          localStride: Int,
          localAddress: MemoryAddress,
          size: MemoryAddressRaw,
          tid: Int,
          context: Option[InstructionContext]
      ): Unit = {
        val localBase = localAddress.raw.toInt * arch.arraySize.toInt
        val localStep = 1 << localStride

        for (i <- size.toInt to 0 by -1) {
          for (j <- 0 until arch.arraySize)
            currentWeights(arch.arraySize - j) = currentWeights(
              arch.arraySize - (j + 1)
            )

          currentWeights(0) =
            if (localAddress.tag == MemoryTag.Zeroes)
              Array.fill(arch.arraySize)(zero)
            else
              finishReadLocal(localBase + i * arch.arraySize * localStep)
        }
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
        val input =
          if (readAccumulatorAddress.tag == MemoryTag.Accumulators)
            Some(
              finishReadAccumulator(
                readAccumulatorAddress.raw.toInt * arch.arraySize.toInt
              )
            )
          else None

        val left =
          if (simdSourceLeft == SIMDSource.Input)
            input.getOrElse(mkArray(arch.arraySize))
          else {
            val base = (simdSourceLeft - 1) * arch.arraySize.toInt
            simdRegistersArray.slice(base, base + arch.arraySize)
          }

        val right =
          if (simdSourceRight == SIMDSource.Input)
            input.getOrElse(mkArray(arch.arraySize))
          else {
            val base = (simdSourceRight - 1) * arch.arraySize.toInt
            simdRegistersArray.slice(base, base + arch.arraySize)
          }

        var (y, base) =
          if (simdDestination == SIMDDestination.Output) {
            if (writeAccumulatorAddress.tag == MemoryTag.Accumulators)
              (
                accumulatorArray,
                writeAccumulatorAddress.raw.toInt * arch.arraySize.toInt
              )
            else
              (mkArray(arch.arraySize), 0)
          } else {
            (
              simdRegistersArray,
              (simdDestination - 1) * arch.arraySize.toInt
            )
          }

        for (i <- 0 until arch.arraySize) {
          val r = simdOp match {
            case SIMDOp.Zero     => zero
            case SIMDOp.Max      => if (left(i) > right(i)) left(i) else right(i)
            case SIMDOp.Add      => left(i) + right(i)
            case SIMDOp.Multiply => left(i) * right(i)
            case SIMDOp.Move     => left(i)
          }

          if (accumulate)
            y(base + i) += r
          else
            y(base + i) = r
        }
      }

      def endEmit(): Unit = {}
    }

    private def mkArray(size: Int): Array[T] = {
      Array.fill(size)(zero)
    }
  }
}
