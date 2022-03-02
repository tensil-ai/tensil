/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.golden

import java.io._

import scala.Numeric.Implicits._
import scala.Ordering.Implicits._
import scala.reflect.ClassTag

import tensil.tools.data.Tensor
import tensil.tools.{TraceContext}
import tensil.{Architecture, ArchitectureDataTypeWithBase, TablePrinter}
import tensil.tools.compiler.{
  LoadWeightsFlags,
  SIMDFlags,
  SIMDOp,
  MatMulFlags,
  DataMoveFlags,
  SIMDSource,
  SIMDDestination,
  MemoryAddressRaw
}

class Processor[T : Numeric : ClassTag](
    dataType: ArchitectureDataTypeWithBase[T],
    arch: Architecture
) {
  val decoder   = new Decoder(arch)
  val executive = new ProcessorExecutive(arch)

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

    try {
      decoder.decode(
        stream,
        executive,
        trace
      )
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

  class ProcessorExecutive(arch: Architecture) extends Executive {
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

    def execMatMul(
        flags: Int,
        localStride: Int,
        localAddress: MemoryAddressRaw,
        accumulatorStride: Int,
        accumulatorAddress: MemoryAddressRaw,
        size: MemoryAddressRaw
    ): Unit = {
      val localBase       = localAddress.toInt * arch.arraySize.toInt
      val accumulatorBase = accumulatorAddress.toInt * arch.arraySize.toInt

      val localStep       = 1 << localStride
      val accumulatorStep = 1 << accumulatorStride

      for (i <- 0 to size.toInt) {
        val x =
          Array(
            Array[T](one) ++ (if ((flags & MatMulFlags.Zeroes) != 0)
                                Array.fill(arch.arraySize)(zero)
                              else {
                                val k =
                                  localBase + i * arch.arraySize * localStep
                                localArray.slice(k, k + arch.arraySize)
                              })
          )

        val y = Tensor.goldenMatMatMul(x, currentWeights)

        for (j <- 0 until arch.arraySize)
          if ((flags & MatMulFlags.Accumulate) != 0)
            accumulatorArray(
              accumulatorBase + i * arch.arraySize * accumulatorStep + j
            ) += y(0)(j)
          else
            accumulatorArray(
              accumulatorBase + i * arch.arraySize * accumulatorStep + j
            ) = y(0)(j)
      }
    }

    def execDataMove(
        flags: Int,
        localStride: Int,
        localAddress: MemoryAddressRaw,
        accumulatorOrDRAMStride: Int,
        accumulatorOrDRAMAddress: MemoryAddressRaw,
        size: MemoryAddressRaw
    ): Unit = {
      val localBase = localAddress.toInt * arch.arraySize.toInt
      val accumulatorOrDRAMBase =
        accumulatorOrDRAMAddress.toInt * arch.arraySize.toInt

      val localStep             = 1 << localStride
      val accumulatorOrDRAMStep = 1 << accumulatorOrDRAMStride

      for (i <- 0 to size.toInt) {
        val x = flags match {
          case DataMoveFlags.AccumulatorToLocal =>
            val k =
              accumulatorOrDRAMBase + i * arch.arraySize * accumulatorOrDRAMStep
            accumulatorArray.slice(k, k + arch.arraySize)
          case DataMoveFlags.DRAM0ToLocal =>
            val k =
              accumulatorOrDRAMBase + i * arch.arraySize * accumulatorOrDRAMStep
            dram0Array.slice(k, k + arch.arraySize)
          case DataMoveFlags.DRAM1ToLocal =>
            val k =
              accumulatorOrDRAMBase + i * arch.arraySize * accumulatorOrDRAMStep
            dram1Array.slice(k, k + arch.arraySize)
          case DataMoveFlags.LocalToAccumulator |
              DataMoveFlags.LocalToAccumulatorAccumulate |
              DataMoveFlags.LocalToDRAM0 | DataMoveFlags.LocalToDRAM1 =>
            val k = localBase + i * arch.arraySize * localStep
            localArray.slice(k, k + arch.arraySize)
        }

        val (y, yBase, accumulate) = flags match {
          case DataMoveFlags.AccumulatorToLocal | DataMoveFlags.DRAM0ToLocal |
              DataMoveFlags.DRAM1ToLocal =>
            (localArray, localBase + i * arch.arraySize * localStep, false)
          case DataMoveFlags.LocalToDRAM0 =>
            (
              dram0Array,
              accumulatorOrDRAMBase + i * arch.arraySize * accumulatorOrDRAMStep,
              false
            )
          case DataMoveFlags.LocalToDRAM1 =>
            (
              dram1Array,
              accumulatorOrDRAMBase + i * arch.arraySize * accumulatorOrDRAMStep,
              false
            )
          case DataMoveFlags.LocalToAccumulator =>
            (
              accumulatorArray,
              accumulatorOrDRAMBase + i * arch.arraySize * accumulatorOrDRAMStep,
              false
            )
          case DataMoveFlags.LocalToAccumulatorAccumulate =>
            (
              accumulatorArray,
              accumulatorOrDRAMBase + i * arch.arraySize * accumulatorOrDRAMStep,
              true
            )
        }

        for (j <- 0 until arch.arraySize)
          if (accumulate)
            y(yBase + j) += x(j)
          else
            y(yBase + j) = x(j)
      }
    }

    def execLoadWeights(
        flags: Int,
        localStride: Int,
        localAddress: MemoryAddressRaw,
        size: MemoryAddressRaw
    ): Unit = {
      val localStep = 1 << localStride

      for (i <- size.toInt to 0 by -1) {
        for (j <- 0 until arch.arraySize)
          currentWeights(arch.arraySize - j) = currentWeights(
            arch.arraySize - (j + 1)
          )

        flags match {
          case LoadWeightsFlags.None =>
            val base = localAddress.toInt * arch.arraySize.toInt
            val k    = base + i * arch.arraySize * localStep

            currentWeights(0) = localArray.slice(k, k + arch.arraySize)
          case LoadWeightsFlags.Zeroes =>
            currentWeights(0) = Array.fill(arch.arraySize)(zero)
        }
      }
    }

    def execSIMD(
        flags: Int,
        simdOp: Int,
        simdSourceLeft: Int,
        simdSourceRight: Int,
        simdDestination: Int,
        writeAccumulatorAddress: MemoryAddressRaw,
        readAccumulatorAddress: MemoryAddressRaw
    ): Unit = {
      val readAccumulatorBase =
        readAccumulatorAddress.toInt * arch.arraySize.toInt

      val left = if (simdSourceLeft == SIMDSource.Input) {
        if ((flags & SIMDFlags.Read) != 0)
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
        if ((flags & SIMDFlags.Read) != 0)
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

      var (y, base, accumulate) =
        if (simdDestination == SIMDDestination.Output) {
          if ((flags & SIMDFlags.Write) != 0)
            (
              accumulatorArray,
              writeAccumulatorAddress.toInt * arch.arraySize.toInt,
              (flags & SIMDFlags.Accumulate) != 0
            )
          else
            (mkArray(arch.arraySize), 0, false)
        } else {
          (
            simdRegistersArray,
            (simdDestination - 1) * arch.arraySize.toInt,
            false
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
