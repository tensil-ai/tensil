/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io._
import scala.collection.mutable
import tensil.tools.{
  CompilerException,
  TraceContext,
  TracepointCondition,
  TracepointsMap
}
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

  private val lirTracepointCollector = new lir.TracepointCollector(
    tracepointConditions,
    resolveRefToObject
  )

  private val lirBroadcast = new lir.Broadcast(
    Seq(
      new lir.StreamGen(layout, fileStream),
      lirTracepointCollector
    ) ++ (if (stats.isDefined) Seq(new lir.StatsGen(layout, stats.get))
          else
            Nil): _*
  )

  def instructionsCount = lirTracepointCollector.instructionsCount
  def instructionTracepointsMaps =
    lirTracepointCollector.instructionTracepointsMaps

  def emitWait(tidToWait: Int, tid: Int): Unit =
    lirBroadcast.emitWait(tidToWait, tid)

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int
  ): Unit =
    lirBroadcast.emitMatMul(
      accumulate,
      localStride,
      localAddress,
      accumulatorStride,
      accumulatorAddress,
      size,
      tid
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
    lirBroadcast.emitSIMD(
      accumulate,
      simdOp,
      simdSourceLeft,
      simdSourceRight,
      simdDestination,
      writeAccumulatorAddress,
      readAccumulatorAddress,
      tid
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
    lirBroadcast.emitDataMove(
      toLocal,
      accumulate,
      localStride,
      localAddress,
      stride,
      address,
      size,
      tid
    )

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw,
      tid: Int
  ): Unit = lirBroadcast.emitLoadWeights(localStride, localAddress, size, tid)

  def endEmit(): Unit = {
    lirBroadcast.endEmit()
    fileStream.close()
  }
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

  def emitSegment(segment: BackendSegment): Unit =
    segments(segment.key) = segment

  def writeSegments(
      programStream: OutputStream,
      printProgramStream: Option[DataOutputStream] = None,
      stats: Option[Stats] = None
  ): Unit = {
    var instructionOffset: Long = 0
    val lirBroadcast = new lir.Broadcast(
      Seq(new lir.StreamGen(layout, programStream)) ++ (if (
                                                          printProgramStream.isDefined
                                                        )
                                                          Seq(
                                                            new lir.Printer(
                                                              printProgramStream.get
                                                            )
                                                          )
                                                        else
                                                          Nil) ++ (if (
                                                                     stats.isDefined
                                                                   )
                                                                     Seq(
                                                                       new lir.StatsGen(
                                                                         layout,
                                                                         stats.get
                                                                       )
                                                                     )
                                                                   else Nil): _*
    )

    val windowSize = layout.arch.numberOfThreads match {
      case 1 => 1
      case 2 => 3
      case _ =>
        throw new CompilerException(
          s"${layout.arch.numberOfThreads} threads are not supported"
        )
    }

    var nextTid = 0

    case class ThreadedPartition(
        init: Option[BackendSegment] = None,
        load: Option[BackendSegment] = None,
        compute: Option[BackendSegment] = None,
        save: Option[BackendSegment] = None,
    ) {
      val tid = nextTid

      nextTid = (nextTid + 1) % layout.arch.numberOfThreads
    }

    var curInit: Option[BackendSegment] = None
    val partitions = Seq.fill(windowSize - 1)(ThreadedPartition()) ++
      (for (
        layerSegments <-
          segments.groupBy(_._1.layer).toSeq.sortBy(_._1).map(_._2)
      )
        yield (for (
          partitionSegments <-
            layerSegments
              .groupBy(p => (p._1.stage, p._1.partition))
              .toSeq
              .sortBy(_._1)
              .map(_._2)
        ) yield {
          val kindSegments = partitionSegments.map {
            case (key, segment) => (key.kind, segment)
          }

          val init = if (partitionSegments.head._1.partition == 0) {
            curInit = kindSegments.get(BackendSegmentKey.Init)
            curInit
          } else if (
            partitionSegments.head._1.partition < layout.arch.numberOfThreads
          ) {
            curInit
          } else None

          ThreadedPartition(
            init = init,
            load = kindSegments.get(BackendSegmentKey.Load),
            compute = kindSegments.get(BackendSegmentKey.Compute),
            save = kindSegments.get(BackendSegmentKey.Save)
          )
        }) ++ Seq.fill(windowSize - 1)(ThreadedPartition())).reduceLeft(_ ++ _)

    val mixer = new lir.Mixer(layout)
    def mixPartitions(window: Seq[ThreadedPartition]) = {
      require(window.size == 1 || window.size == 3)

      val streamsByTid =
        (if (window.size == 3)
           Seq(
             (window(0).tid, window(0).save),
             (window(2).tid, window(2).init),
             (window(2).tid, window(2).load),
             (window(1).tid, window(1).compute)
           )
         else if (window.size == 1)
           Seq(
             (window(0).tid, window(0).init),
             (window(0).tid, window(0).load),
             (window(0).tid, window(0).compute),
             (window(0).tid, window(0).save)
           )
         else Nil)
          .filter(_._2.isDefined)
          .map {
            case (tid, segment) =>
              if (printProgramStream.isDefined)
                printProgramStream.get.writeBytes(
                  s"; TID $tid: ${BackendSegmentKeyHelper(segment.get.key)}\r\n"
                )

              (tid, new FileInputStream(segment.get.file))
          }

      val parsersByTid =
        streamsByTid
          .groupBy(_._1)
          .map {
            case (tid, g) =>
              (tid -> lir.Parser.concat(
                g.map(p => new lir.StreamParser(layout.arch, p._2)): _*
              ))
          }

      mixer.mix(parsersByTid, lirBroadcast)

      streamsByTid.foreach(_._2.close())
    }

    for (i <- 0 until partitions.size - (windowSize - 1)) {
      mixPartitions(partitions.slice(i, i + windowSize))
    }

    lirBroadcast.endEmit()

    val filesToDelete = segments.map(s => Seq(s._2.file)).flatten

    for (file <- filesToDelete)
      file.delete()

    /*for ((key, segment) <- segments) {
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
    }*/
  }

  def instructionsCount = segments.values.map(_.instructionsCount).sum
}
