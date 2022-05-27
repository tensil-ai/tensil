/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler.lir

import scala.collection.mutable

import tensil.tools.compiler.{
  LIR,
  InstructionContext,
  MemoryAddress,
  MemoryAddressHelper,
  MemoryAddressRaw,
  MemoryTag,
  Estimator,
  Opcode
}
import tensil.Architecture

class Parallelizer(arch: Architecture) {
  def emit(
      parsersByTid: Map[Int, Parser],
      targetLir: LIR
  ): Unit = {
    val threads = (0 until arch.numberOfThreads)
      .map(i =>
        new LIR {
          val tid           = i
          val estimator     = new Estimator(arch)
          var currentCycles = 0L
          val cyclesQueue   = mutable.Queue.empty[Long]

          private def countCycles(cycles: Long): Unit = {
            currentCycles += cycles
            cyclesQueue.enqueue(cycles)

            while (cyclesQueue.size > arch.threadQueueDepth + 1)
              cyclesQueue.dequeue()
          }

          private def adjustLocalAddress(address: MemoryAddress) =
            if (address.tag == MemoryTag.Local) {

              MemoryAddress(
                MemoryTag.Local,
                address.ref,
                address.raw + arch.threadLocalDepth * tid
              )
            } else address

          def emitPaddingNoOps(cycles: Long) =
            for (_ <- 0L until cycles) emitWait(tid)

          override def emitWait(
              tidToWait: Int,
              ignoredTid: Int,
              context: Option[InstructionContext]
          ): Unit = {
            countCycles(estimator.estimateCyclesAndEnergy(Opcode.Wait).cycles)
            targetLir.emitWait(tidToWait, tid, context)
          }

          override def emitMatMul(
              accumulate: Boolean,
              localStride: Int,
              localAddress: MemoryAddress,
              accumulatorStride: Int,
              accumulatorAddress: MemoryAddress,
              size: MemoryAddressRaw,
              ignoredTid: Int,
              context: Option[InstructionContext]
          ): Unit = {
            countCycles(
              estimator
                .estimateCyclesAndEnergy(Opcode.MatMul, Some(size))
                .cycles
            )
            targetLir.emitMatMul(
              accumulate,
              localStride,
              adjustLocalAddress(localAddress),
              accumulatorStride,
              accumulatorAddress,
              size,
              tid,
              context
            )
          }

          override def emitSIMD(
              accumulate: Boolean,
              simdOp: Int,
              simdSourceLeft: Int,
              simdSourceRight: Int,
              simdDestination: Int,
              writeAccumulatorAddress: MemoryAddress,
              readAccumulatorAddress: MemoryAddress,
              ignoredTid: Int,
              context: Option[InstructionContext]
          ): Unit = {
            countCycles(estimator.estimateCyclesAndEnergy(Opcode.SIMD).cycles)
            targetLir.emitSIMD(
              accumulate,
              simdOp,
              simdSourceLeft,
              simdSourceRight,
              simdDestination,
              writeAccumulatorAddress,
              readAccumulatorAddress,
              tid,
              context
            )
          }

          override def emitDataMove(
              toLocal: Boolean,
              accumulate: Boolean,
              localStride: Int,
              localAddress: MemoryAddress,
              stride: Int,
              address: MemoryAddress,
              size: MemoryAddressRaw,
              ignoredTid: Int,
              context: Option[InstructionContext]
          ): Unit = {
            countCycles(
              estimator
                .estimateCyclesAndEnergy(
                  Opcode.DataMove,
                  Some(size),
                  StreamGen.mkDataMoveFlags(toLocal, accumulate, address.tag)
                )
                .cycles
            )
            targetLir.emitDataMove(
              toLocal,
              accumulate,
              localStride,
              adjustLocalAddress(localAddress),
              stride,
              address,
              size,
              tid,
              context
            )
          }

          override def emitLoadWeights(
              localStride: Int,
              localAddress: MemoryAddress,
              size: MemoryAddressRaw,
              ignoredTid: Int,
              context: Option[InstructionContext]
          ): Unit = {
            countCycles(
              estimator
                .estimateCyclesAndEnergy(Opcode.LoadWeights, Some(size))
                .cycles
            )
            targetLir.emitLoadWeights(
              localStride,
              adjustLocalAddress(localAddress),
              size,
              tid,
              context
            )
          }

          def endEmit(): Unit = {}
        }
      )
      .toSeq

    def nextThread =
      threads
        .filter(m => parsersByTid.filter(_._2.hasNext).contains(m.tid))
        .sortBy(_.currentCycles)
        .headOption
    var currentThread = nextThread

    while (currentThread.isDefined) {
      val currentParser = parsersByTid(currentThread.get.tid)

      currentParser.parseNext(currentThread.get)
      currentThread = nextThread
    }

    /**
      * Pad threads with NoOps for the number of cycles it takes
      * to clear the longest queue among longer threads (both in
      * terms of cycles) or until reaching cycles of the longest
      * thread, whichever comes first.
      *
      * This in the future will be replaced with the barrier consisting
      * of mutual WAITs.
      */
    val threadTotalCycles  = threads.map(_.currentCycles)
    val threadQueuedCycles = threads.map(_.cyclesQueue.sum)

    for (thread <- threads) {
      val longerThreads = threads.filter(t =>
        t.tid != thread.tid &&
          threadTotalCycles(t.tid) > threadTotalCycles(thread.tid)
      )

      if (!longerThreads.isEmpty) {
        val cyclesToPadLongestQueue =
          longerThreads.map(t => threadQueuedCycles(t.tid)).max
        val cyclesToPadLongestThread =
          longerThreads
            .map(t => threadTotalCycles(t.tid))
            .max - threadTotalCycles(thread.tid)

        thread.emitPaddingNoOps(
          Math.min(cyclesToPadLongestQueue, cyclesToPadLongestThread)
        )
      }
    }
  }
}
