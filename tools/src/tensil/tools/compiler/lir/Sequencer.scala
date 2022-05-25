/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler.lir

import scala.collection.mutable

import tensil.InstructionLayout
import tensil.tools.compiler.{
  LIR,
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
    targetLir: LIR
) extends LIR {
  private val estimator = new Estimator(arch)

  case class Op(
      var cycles: Long,
      run: () => Unit
  )

  private val opQueues =
    Array.fill(arch.numberOfThreads)(mutable.Queue.empty[Op])

  private def execute(maxQueueSize: Int): Unit = {
    val nonEmptyTids =
      opQueues.map(_.size).zipWithIndex.filter(_._1 != 0).map(_._2)
    val runs = mutable.ArrayBuffer.empty[() => Unit]

    val estimates = if (nonEmptyTids.size > 1) {
      val minCycles =
        nonEmptyTids.map(opQueues(_).front.cycles).min
      for (tid <- nonEmptyTids.toSeq)
        yield
          if (opQueues(tid).front.cycles == minCycles)
            runs += opQueues(tid).dequeue().run
          else
            opQueues(tid).front.cycles -= minCycles

    } else if (nonEmptyTids.size == 1) {
      val queue = opQueues(nonEmptyTids(0))

      if (queue.size > maxQueueSize) {
        val dequeueSize = queue.size - maxQueueSize
        for (_ <- 0 until dequeueSize) yield runs += queue.dequeue().run
      }
    }

    runs.foreach(_())
  }

  private def submit(
      tid: Int,
      cycles: Long,
      run: () => Unit
  ): Unit = {
    opQueues(tid).enqueue(Op(cycles, run))

    execute(arch.threadQueueDepth)
  }

  def emitWait(tidToWait: Int, tid: Int): Unit = {
    submit(
      tid,
      estimator.estimateCyclesAndEnergy(Opcode.Wait).cycles,
      () => targetLir.emitWait(tidToWait, tid)
    )
  }

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int
  ): Unit =
    submit(
      tid,
      estimator.estimateCyclesAndEnergy(Opcode.MatMul, Some(size)).cycles,
      () =>
        targetLir.emitMatMul(
          accumulate,
          localStride,
          localAddress,
          accumulatorStride,
          accumulatorAddress,
          size,
          tid
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
      tid: Int
  ): Unit =
    submit(
      tid,
      estimator.estimateCyclesAndEnergy(Opcode.SIMD).cycles,
      () =>
        targetLir.emitSIMD(
          accumulate,
          simdOp,
          simdSourceLeft,
          simdSourceRight,
          simdDestination,
          writeAccumulatorAddress,
          readAccumulatorAddress,
          tid
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
      tid: Int
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
      () =>
        targetLir.emitDataMove(
          toLocal,
          accumulate,
          localStride,
          localAddress,
          stride,
          address,
          size,
          tid
        )
    )

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int
  ): Unit =
    submit(
      tid,
      estimator.estimateCyclesAndEnergy(Opcode.LoadWeights, Some(size)).cycles,
      () =>
        targetLir.emitLoadWeights(
          localStride,
          localAddress,
          size,
          tid
        )
    )

  def endEmit(): Unit =
    while (opQueues.exists(!_.isEmpty))
      execute(0)
}
