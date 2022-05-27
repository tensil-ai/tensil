/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler.lir

import scala.collection.mutable

import tensil.InstructionLayout
import tensil.tools.compiler.{
  LIR,
  InstructionContext,
  MemoryAddress,
  MemoryAddressHelper,
  MemoryAddressRaw,
  MemoryTag,
  Stats,
  Estimator,
  Estimate,
  Opcode,
  DataMoveFlags
}
import tensil.Architecture

class Sequencer(
    arch: Architecture,
    readLir: LIR,
    writeLir: LIR
) extends LIR {
  private val estimator = new Estimator(arch)

  case class Instruction(
      var cycles: Long,
      execute: (LIR) => Unit
  )

  private val instructionQueues =
    Array.fill(arch.numberOfThreads)(mutable.Queue.empty[Instruction])

  private def executeQueue(maxQueueSize: Int): Unit = {
    val nonEmptyTids =
      instructionQueues.map(_.size).zipWithIndex.filter(_._1 != 0).map(_._2)

    def dequeueAndExecute(queue: mutable.Queue[Instruction]): Unit = {
      queue.dequeue().execute(writeLir)

      if (!queue.isEmpty)
        queue.front.execute(readLir)
    }

    val estimates = if (nonEmptyTids.size > 1) {
      val minCycles =
        nonEmptyTids.map(instructionQueues(_).front.cycles).min
      for (tid <- nonEmptyTids.toSeq)
        if (instructionQueues(tid).front.cycles == minCycles)
          dequeueAndExecute(instructionQueues(tid))
        else
          instructionQueues(tid).front.cycles -= minCycles

    } else if (nonEmptyTids.size == 1) {
      val queue = instructionQueues(nonEmptyTids(0))

      if (queue.size > maxQueueSize) {
        val dequeueSize = queue.size - maxQueueSize
        for (_ <- 0 until dequeueSize) dequeueAndExecute(queue)
      }
    }
  }

  private def submit(
      tid: Int,
      cycles: Long,
      execute: (LIR) => Unit
  ): Unit = {
    val queue    = instructionQueues(tid)
    val wasEmpty = queue.isEmpty
    queue.enqueue(Instruction(cycles, execute))

    if (wasEmpty)
      execute(readLir)

    executeQueue(arch.threadQueueDepth + 1)
  }

  def emitWait(
      tidToWait: Int,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit = {
    submit(
      tid,
      estimator.estimateCyclesAndEnergy(Opcode.Wait).cycles,
      (lir) => lir.emitWait(tidToWait, tid, context)
    )
  }

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
    submit(
      tid,
      estimator.estimateCyclesAndEnergy(Opcode.MatMul, Some(size)).cycles,
      (lir) =>
        lir.emitMatMul(
          accumulate,
          localStride,
          localAddress,
          accumulatorStride,
          accumulatorAddress,
          size,
          tid,
          context
        )
    )

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
    submit(
      tid,
      estimator.estimateCyclesAndEnergy(Opcode.SIMD).cycles,
      (lir) =>
        lir.emitSIMD(
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
    )

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
  ): Unit =
    submit(
      tid,
      estimator
        .estimateCyclesAndEnergy(
          Opcode.DataMove,
          Some(size),
          StreamGen.mkDataMoveFlags(toLocal, accumulate, address.tag)
        )
        .cycles,
      (lir) =>
        lir.emitDataMove(
          toLocal,
          accumulate,
          localStride,
          localAddress,
          stride,
          address,
          size,
          tid,
          context
        )
    )

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int,
      context: Option[InstructionContext]
  ): Unit =
    submit(
      tid,
      estimator.estimateCyclesAndEnergy(Opcode.LoadWeights, Some(size)).cycles,
      (lir) =>
        lir.emitLoadWeights(
          localStride,
          localAddress,
          size,
          tid,
          context
        )
    )

  def endEmit(): Unit = {
    while (instructionQueues.exists(!_.isEmpty))
      executeQueue(0)

    readLir.endEmit()
    writeLir.endEmit()
  }
}
