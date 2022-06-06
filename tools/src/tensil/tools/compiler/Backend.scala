/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io.{OutputStream, FileInputStream}

import scala.collection.mutable
import tensil.tools.{
  CompilerException,
  TraceContext,
  TracepointCondition,
  TracepointsMap
}
import tensil.{ArchitectureDataType, InstructionLayout}

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
      printProgramFileName: Option[String] = None,
      stats: Option[Stats] = None
  ): Unit = {
    var instructionOffset: Long = 0
    val writingLir = new lir.InstructionAddressInjector(
      new lir.Broadcast(
        Seq(
          new lir.StreamGen(layout, programStream),
          new lir.TracepointEmitter(traceContext)
        ) ++ (if (printProgramFileName.isDefined)
                Seq(
                  new lir.Printer(
                    printProgramFileName.get
                  )
                )
              else
                Nil) ++ (if (stats.isDefined)
                           Seq(
                             new lir.StatsGen(
                               layout.arch,
                               stats.get
                             )
                           )
                         else
                           Nil): _*
      )
    )

    val windowSize = layout.arch.numberOfThreads match {
      case 1 => 1
      /**
        * For 2 threads we interlace LIR from init-load-save and
        * compute segments. This is achieved by looking at a
        * moving window of 3 partitions and taking save segment
        * from the first, compute from the second, and init-load
        * from the third.
        */
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

    /**
      * Pad with `windowSize - 1` in the beginning and the end
      * to ensure that first init-load and last save segments
      * are handled correctly by the windowing.
      *
      * Pad between layers to ensure that segments between layers
      * don't get parallelized since this may cause violation of
      * data dependencies.
      */
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

          /**
            * Replicate init segment for each thread to ensure
            * that init consts are loaded in theard's local memory
            */
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

    val parallelizer = new lir.Parallelizer(layout.arch)
    def parallelizePartitions(window: Seq[ThreadedPartition]) = {
      require(window.size == 1 || window.size == 3)

      val streamsAndTracepointsMapsByTid =
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
              (
                tid,
                (
                  new FileInputStream(segment.get.file),
                  segment.get.instructionTracepointsMaps
                )
              )
          }

      val parsersByTid =
        streamsAndTracepointsMapsByTid
          .groupBy(_._1)
          .map {
            case (tid, g) =>
              (tid -> lir.Parser.concat(
                g.map(p =>
                  lir.Parser.injectInstructionAddress(
                    lir.Parser.injectInstructionTracepointsMaps(
                      new lir.StreamParser(
                        layout.arch,
                        p._2._1,
                        closeAtEof = true
                      ),
                      p._2._2
                    )
                  )
                ): _*
              ))
          }

      parallelizer.emit(parsersByTid, writingLir)
    }

    for (i <- 0 until partitions.size - (windowSize - 1)) {
      parallelizePartitions(partitions.slice(i, i + windowSize))
    }

    writingLir.endEmit()

    val filesToDelete = segments.map(s => Seq(s._2.file)).flatten

    for (file <- filesToDelete)
      file.delete()
  }

  def instructionsCount = segments.values.map(_.instructionsCount).sum
}
