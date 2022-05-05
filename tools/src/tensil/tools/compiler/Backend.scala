/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io._
import scala.collection.mutable
import tensil.tools.{TraceContext, TracepointCondition, TracepointsMap}
import tensil.{ArchitectureDataType, InstructionLayout}

class BackendSegment(
    val key: BackendSegmentKey,
    layout: InstructionLayout,
    stats: Option[Stats],
    tracepointConditions: Seq[TracepointCondition],
    resolveRefToObject: (MemoryRef) => Option[MemoryObject] = (ref) => None
) extends LIR {
  val file = File.createTempFile("segment_", ".tprog")

  private val fileStream = new FileOutputStream(file)

  private val lirTracepointCollector = new LIRTracepointCollector(
    tracepointConditions,
    resolveRefToObject
  )

  private val lirBroadcast = new LIRBroadcast(
    Seq(
      new LIRGen(layout, fileStream),
      lirTracepointCollector
    ) ++ (if (stats.isDefined) Seq(new LIREstimator(layout, stats.get))
          else
            Nil)
  )

  def instructionsCount = lirTracepointCollector.instructionsCount
  def instructionTracepointsMaps =
    lirTracepointCollector.instructionTracepointsMaps

  def close(): Unit = fileStream.close()

  def emitNoOp(): Unit = lirBroadcast.emitNoOp()

  def emitWait(tidToWait: Int): Unit = lirBroadcast.emitWait(tidToWait)

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit =
    lirBroadcast.emitMatMul(
      accumulate,
      localStride,
      localAddress,
      accumulatorStride,
      accumulatorAddress,
      size
    )

  def emitSIMD(
      accumulate: Boolean,
      simdOp: Int,
      simdSourceLeft: Int,
      simdSourceRight: Int,
      simdDestination: Int,
      writeAccumulatorAddress: MemoryAddress,
      readAccumulatorAddress: MemoryAddress
  ): Unit =
    lirBroadcast.emitSIMD(
      accumulate,
      simdOp,
      simdSourceLeft,
      simdSourceRight,
      simdDestination,
      writeAccumulatorAddress,
      readAccumulatorAddress
    )

  def emitDataMove(
      toLocal: Boolean,
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      stride: Int,
      address: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit =
    lirBroadcast.emitDataMove(
      toLocal,
      accumulate,
      localStride,
      localAddress,
      stride,
      address,
      size
    )

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit = lirBroadcast.emitLoadWeights(localStride, localAddress, size)
}

object BackendSegmentKey {
  val Init    = 0
  val Load    = 1
  val Compute = 2
  val Save    = 3

  def apply(
      layer: Int,
      stage: Int,
      partition: Int,
      kind: Int
  ): BackendSegmentKey = (layer, stage, partition, kind)
}

class Backend(
    layout: InstructionLayout,
    tracepointConditions: Seq[TracepointCondition] = Nil,
    resolveRefToObject: (MemoryRef) => Option[MemoryObject] = (ref) => None,
    traceContext: TraceContext = TraceContext.empty,
) {
  private val segments =
    mutable.SortedMap.empty[BackendSegmentKey, BackendSegment]

  def mkSegment(
      key: BackendSegmentKey,
      stats: Option[Stats] = None
  ): BackendSegment =
    new BackendSegment(
      key = key,
      layout = layout,
      stats = stats,
      tracepointConditions = tracepointConditions,
      resolveRefToObject = resolveRefToObject
    )

  def finalizeSegment(segment: BackendSegment): Unit = {
    segment.close()
    segments(segment.key) = segment
  }

  def writeSegments(
      programStream: OutputStream,
      printProgramStream: Option[DataOutputStream] = None,
      stats: Option[Stats] = None
  ): Unit = {
    var instructionOffset: Long = 0
    val lir = new LIRBroadcast(
      Seq(new LIRGen(layout, programStream)) ++ (if (
                                                   printProgramStream.isDefined
                                                 )
                                                   Seq(
                                                     new LIRPrinter(
                                                       printProgramStream.get
                                                     )
                                                   )
                                                 else
                                                   Nil) ++ (if (stats.isDefined)
                                                              Seq(
                                                                new LIREstimator(
                                                                  layout,
                                                                  stats.get
                                                                )
                                                              )
                                                            else Nil)
    )

    def decodeSegment(segment: BackendSegment, lir: LIR): Unit = {
      val inputStream = new FileInputStream(segment.file)
      val lirDecoder  = new LIRDecoder(layout.arch)

      lirDecoder.decode(inputStream, lir)

      inputStream.close()
    }

    var nextTid = 0

    case class Block(
        init: Option[BackendSegment] = None,
        load: Option[BackendSegment] = None,
        compute: Option[BackendSegment] = None,
        save: Option[BackendSegment] = None,
    ) {
      val tid = nextTid

      nextTid = (nextTid + 1) % 2
    }

    var prev0Block = Block()
    var prev1Block = Block()

    var curInit: Option[BackendSegment] = None

    val blocks =
      for (
        blockSegments <-
          segments
            .groupBy(p => (p._1.layer, p._1.stage, p._1.partition))
            .toSeq
            .sortBy(_._1)
            .map(_._2)
      ) yield {
        val kindSegments = blockSegments.map {
          case (key, segment) => (key.kind, segment)
        }

        val init = if (blockSegments.head._1.partition == 0) {
          curInit = kindSegments.get(BackendSegmentKey.Init)
          curInit
        } else if (blockSegments.head._1.partition == 1) {
          curInit
        } else None

        Block(
          init = init,
          load = kindSegments.get(BackendSegmentKey.Load),
          compute = kindSegments.get(BackendSegmentKey.Compute),
          save = kindSegments.get(BackendSegmentKey.Save)
        )
      }

    val stream = new DataOutputStream(new FileOutputStream("threads.csv"))
    var index  = 0

    def overlay(curBlock: Block, prev0Block: Block, prev1Block: Block) = {
      val initLoadSaveStats = new Stats()
      val computeStats      = new Stats()

      val initLoadSaveLir = new LIREstimator(layout, initLoadSaveStats)
      val computeLir      = new LIREstimator(layout, computeStats)

      if (prev1Block.save.isDefined) {
        println(
          s"TID ${prev1Block.tid}: ${BackendSegmentKeyHelper(prev1Block.save.get.key)}"
        )
        decodeSegment(prev1Block.save.get, initLoadSaveLir)
      }
      if (curBlock.init.isDefined) {
        println(
          s"TID ${curBlock.tid}: ${BackendSegmentKeyHelper(curBlock.init.get.key)}"
        )
        decodeSegment(curBlock.init.get, initLoadSaveLir)
      }
      if (curBlock.load.isDefined) {
        println(
          s"TID ${curBlock.tid}: ${BackendSegmentKeyHelper(curBlock.load.get.key)}"
        )
        decodeSegment(curBlock.load.get, initLoadSaveLir)
      }
      if (prev0Block.compute.isDefined) {
        println(
          s"TID ${prev0Block.tid}: ${BackendSegmentKeyHelper(prev0Block.compute.get.key)}"
        )
        decodeSegment(prev0Block.compute.get, computeLir)
      }

      println("BARRIER")

      stream.writeBytes(
        s"${index},${initLoadSaveStats.totalCycles},${computeStats.totalCycles}\r\n"
      )

      index += 1
    }

    for (curBlock <- blocks) {
      overlay(curBlock, prev0Block, prev1Block)

      prev1Block = prev0Block
      prev0Block = curBlock
    }

    overlay(Block(), prev0Block, prev1Block)
    overlay(Block(), Block(), prev0Block)

    stream.close()

    for ((key, segment) <- segments) {
      if (printProgramStream.isDefined)
        printProgramStream.get.writeBytes(
          s";\r\n; ${BackendSegmentKeyHelper(key)}\r\n;\r\n"
        )

      decodeSegment(segment, lir)
      segment.file.delete()

      for (
        (offset, instructionTracepointsMap) <-
          segment.instructionTracepointsMaps
      ) {
        traceContext.emitTracepoints(
          instructionOffset + offset,
          instructionTracepointsMap
        )
      }

      instructionOffset += segment.instructionsCount
    }
  }

  def instructionsCount = segments.values.map(_.instructionsCount).sum
}
