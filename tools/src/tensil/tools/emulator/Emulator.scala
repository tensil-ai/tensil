/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.emulator

import java.io._

import scala.Numeric.Implicits._
import scala.Ordering.Implicits._
import scala.reflect.ClassTag

import tensil.tools.data.Tensor
import tensil.tools.{TraceContext}
import tensil.{
  Architecture,
  ArchitectureDataTypeWithBase,
  TablePrinter,
  emulator
}
import tensil.tools.compiler.{
  LIRStreamParser,
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
  LIRBroadcast
}
import tensil.NumericWithMAC
import tensil.tools.compiler.LIRBroadcast

class Emulator[T : NumericWithMAC : ClassTag](
    dataType: ArchitectureDataTypeWithBase[T],
    arch: Architecture
) {
  private val emulatorExecutive = new EmulatorExecutive(arch)

  private def arrayToStream(bytes: Array[Byte]): InputStream = {
    new ByteArrayInputStream(bytes)
  }

  def writeDRAM0(addresses: Seq[MemoryAddressRaw], stream: InputStream): Unit =
    emulatorExecutive.writeDRAM0(addresses, new DataInputStream(stream))
  def writeDRAM0(addresses: Seq[MemoryAddressRaw], bytes: Array[Byte]): Unit =
    writeDRAM0(addresses, arrayToStream(bytes))

  def readDRAM0(addresses: Seq[MemoryAddressRaw], stream: OutputStream): Unit =
    emulatorExecutive.readDRAM0(addresses, new DataOutputStream(stream))
  def readDRAM0(addresses: Seq[MemoryAddressRaw]): Array[Byte] = {
    val bytesStream = new ByteArrayOutputStream()
    emulatorExecutive.readDRAM0(addresses, new DataOutputStream(bytesStream))
    bytesStream.toByteArray()
  }

  def writeDRAM1(size: MemoryAddressRaw, stream: InputStream) =
    emulatorExecutive.writeDRAM1(0L until size, new DataInputStream(stream))
  def writeDRAM1(size: MemoryAddressRaw, bytes: Array[Byte]): Unit =
    writeDRAM1(size, arrayToStream(bytes))

  def run(programBytes: Array[Byte], trace: ExecutiveTrace): Unit =
    run(new ByteArrayInputStream(programBytes), trace)

  def run(stream: InputStream, trace: ExecutiveTrace): Unit = {
    val startTime = System.nanoTime()

    try {
      val emulatorTrace = new EmulatorTrace(trace, emulatorExecutive)
      val lirParser     = new LIRStreamParser(arch, stream)
      val lir           = new LIRBroadcast(Seq(emulatorTrace, emulatorExecutive))

      lirParser.parseAll(lir)

      emulatorTrace.runTrace()
    } finally {
      val endTime = System.nanoTime()

      val tb = new TablePrinter(Some("GOLDEN PROCESSOR SUMMARY"))
      tb.addNamedLine(
        "Execution time (sec)",
        (endTime - startTime).toFloat / 1e9f
      )
      print(tb)

      dataType.reportAndResetOverUnderflowStats()
    }
  }

  private class EmulatorTrace(trace: ExecutiveTrace, executive: Executive)
      extends LIR {
    var instructionOffset = 0L

    def runTrace(): Unit = {
      trace.runTrace(instructionOffset, executive)
      instructionOffset += 1
    }

    def emitWait(tidToWait: Int, tid: Int): Unit = runTrace()

    def emitMatMul(
        accumulate: Boolean,
        localStride: Int,
        localAddress: MemoryAddress,
        accumulatorStride: Int,
        accumulatorAddress: MemoryAddress,
        size: MemoryAddressRaw,
        tid: Int
    ): Unit = runTrace()

    def emitSIMD(
        accumulate: Boolean,
        simdOp: Int,
        simdSourceLeft: Int,
        simdSourceRight: Int,
        simdDestination: Int,
        writeAccumulatorAddress: MemoryAddress,
        readAccumulatorAddress: MemoryAddress,
        tid: Int
    ): Unit = runTrace()

    def emitDataMove(
        toLocal: Boolean,
        accumulate: Boolean,
        localStride: Int,
        localAddress: MemoryAddress,
        stride: Int,
        address: MemoryAddress,
        size: MemoryAddressRaw,
        tid: Int
    ): Unit = runTrace()

    def emitLoadWeights(
        localStride: Int,
        localAddress: MemoryAddress,
        size: MemoryAddressRaw,
        tid: Int
    ): Unit = runTrace()
  }

  private class EmulatorExecutive(arch: Architecture)
      extends Executive
      with LIR {
    val zero = implicitly[Numeric[T]].zero
    val one  = implicitly[Numeric[T]].one

    val localArray       = mkArray(arch.arraySize * arch.localDepth.toInt)
    val dram0Array       = mkArray(arch.arraySize * arch.dram0Depth.toInt)
    val dram1Array       = mkArray(arch.arraySize * arch.dram1Depth.toInt)
    val accumulatorArray = mkArray(arch.arraySize * arch.accumulatorDepth.toInt)
    val simdRegistersArray = mkArray(
      arch.arraySize * arch.simdRegistersDepth.toInt
    )

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

    def emitWait(tidToWait: Int, tid: Int): Unit = {}

    def emitMatMul(
        accumulate: Boolean,
        localStride: Int,
        localAddress: MemoryAddress,
        accumulatorStride: Int,
        accumulatorAddress: MemoryAddress,
        size: MemoryAddressRaw,
        tid: Int
    ): Unit = {
      val localBase       = localAddress.raw.toInt * arch.arraySize.toInt
      val accumulatorBase = accumulatorAddress.raw.toInt * arch.arraySize.toInt

      val localStep       = 1 << localStride
      val accumulatorStep = 1 << accumulatorStride

      for (i <- 0 to size.toInt) {
        val x =
          Array(
            Array[T](one) ++ (if (localAddress.tag == MemoryTag.Zeroes)
                                Array.fill(arch.arraySize)(zero)
                              else {
                                val k =
                                  localBase + i * arch.arraySize * localStep
                                localArray.slice(k, k + arch.arraySize)
                              })
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
        tid: Int
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
              accumulatorArray.slice(k, k + arch.arraySize)

            case MemoryTag.Vars =>
              dram0Array.slice(k, k + arch.arraySize)

            case MemoryTag.Consts =>
              dram1Array.slice(k, k + arch.arraySize)
          }
        } else {
          val k = localBase + i * arch.arraySize * localStep
          localArray.slice(k, k + arch.arraySize)
        }

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
        tid: Int
    ): Unit = {
      val localStep = 1 << localStride

      for (i <- size.toInt to 0 by -1) {
        for (j <- 0 until arch.arraySize)
          currentWeights(arch.arraySize - j) = currentWeights(
            arch.arraySize - (j + 1)
          )

        if (localAddress.tag == MemoryTag.Zeroes)
          currentWeights(0) = Array.fill(arch.arraySize)(zero)
        else {
          val base = localAddress.raw.toInt * arch.arraySize.toInt
          val k    = base + i * arch.arraySize * localStep

          currentWeights(0) = localArray.slice(k, k + arch.arraySize)
        }
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
        tid: Int
    ): Unit = {
      val readAccumulatorBase =
        readAccumulatorAddress.raw.toInt * arch.arraySize.toInt

      val left = if (simdSourceLeft == SIMDSource.Input) {
        if (readAccumulatorAddress.tag == MemoryTag.Accumulators)
          accumulatorArray.slice(
            readAccumulatorBase,
            readAccumulatorBase + arch.arraySize
          )
        else
          mkArray(arch.arraySize)
      } else {
        val base = (simdSourceLeft - 1) * arch.arraySize.toInt
        simdRegistersArray.slice(base, base + arch.arraySize)
      }

      val right = if (simdSourceRight == SIMDSource.Input) {
        if (readAccumulatorAddress.tag == MemoryTag.Accumulators)
          accumulatorArray.slice(
            readAccumulatorBase,
            readAccumulatorBase + arch.arraySize
          )
        else
          mkArray(arch.arraySize)
      } else {
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

    private def mkArray(size: Int): Array[T] = {
      Array.fill(size)(zero)
    }
  }
}
