/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import tensil.TablePrinter

class StandardScheduler2(layerIndex: Int, context: StandardSchedulingContext2)
    extends Scheduler(layerIndex, context) {

  override protected def doLower(
      roots: Seq[MemoryAddress],
      backend: Backend
  ): SchedulerResult = {
    val nodes = traverseRoots(roots)

    val accumulatorSize = estimateAccumulatorSize(nodes)
    val localSize       = estimateLocalSize(nodes)

    val name             = s"LAYER $layerIndex"
    val maximumRootsSize = roots.size

    val accumulatorUtilization =
      accumulatorSize.toFloat / context.options.arch.accumulatorDepth.toFloat
    val localUtilization =
      localSize.toFloat / context.options.arch.localDepth.toFloat

    val stats = new Stats()

    val kinds = Seq(
      BackendSegmentKey.Init,
      BackendSegmentKey.Load,
      BackendSegmentKey.Compute,
      BackendSegmentKey.Save
    )
    val statsByKind = kinds.map(kind => kind -> new Stats()).toMap
    val segmentsByKind = kinds
      .map(kind =>
        kind -> backend.mkSegment(
          BackendSegmentKey(layerIndex, 0, 0, kind),
          Some(statsByKind(kind))
        )
      )
      .toMap

    val localAllocator =
      RenamingMemoryAllocator(
        context.localSpace,
        Set(MemoryTag.DRAM1, MemoryTag.DRAM0)
      )

    lowerLoadConsts(
      segmentsByKind(BackendSegmentKey.Init).segmentLir,
      localAllocator,
      nodes
    )

    lowerLoadVars(
      segmentsByKind(BackendSegmentKey.Load).segmentLir,
      localAllocator,
      nodes
    )

    lowerCompute(
      segmentsByKind(BackendSegmentKey.Compute).segmentLir,
      localAllocator,
      nodes
    )

    lowerSaveVars(
      segmentsByKind(BackendSegmentKey.Save).segmentLir,
      localAllocator,
      nodes
    )

    localAllocator.free()

    segmentsByKind.values.foreach(_.segmentLir.endEmit())
    segmentsByKind.values.foreach(backend.emitSegment(_))
    statsByKind.values.foreach(stats.add(_))

    val instructionsCount = segmentsByKind.values.map(_.instructionsCount).sum

    if (context.options.printProgress) {
      println(
        s"LIR emitted for ${instructionsCount} instruction(s)"
      )
    }

    val macEfficiency = Stats.macEfficiency(stats, context.options.arch, macs)

    if (context.options.printSchedulerSummary) {
      val tb = new TablePrinter(Some(s"$name SCHEDULER SUMMARY"))
      tb.addNamedLine("Partition results size", maximumRootsSize)
      tb.addNamedLine("Partition accumulator size", accumulatorSize)
      tb.addNamedLine("Partition local size", localSize)
      Stats.printSummary(stats, tb, context.options.arch, Some(macs))
      tb.addNamedLine(
        "Total number of instructions",
        instructionsCount
      )
      tb.addNamedLine(
        "Accumulator utilization (%)",
        accumulatorUtilization * 100f
      )
      tb.addNamedLine("Local utilization (%)", localUtilization * 100f)
      val (macsLetter, macsDivisor) =
        Stats.getUnitsLetterAndDivisor(macs)
      tb.addNamedLine(
        s"True MACs (${macsLetter}MAC)",
        macs.toFloat / macsDivisor
      )
      tb.addNamedLine("MAC efficiency (%)", macEfficiency * 100f)
      print(tb)

      if (context.options.printInstructionsSummary) {
        Stats.printCompositionSummary(name, stats)
        Stats.printCyclesSummary(name, stats)
        Stats.printEnergySummary(name, stats)
      }

      if (context.options.printStridesSummary) {
        def printStrideStats(
            title: String,
            select: StrideStats => Any
        ): Unit = {
          val tb = new TablePrinter(Some(title), true)
          Stats.printStrideStats(
            context.options.arch.stride0Depth,
            context.options.arch.stride1Depth,
            stats,
            select,
            tb
          )
          print(tb)
        }

        printStrideStats(s"$name STRIDES COUNT SUMMARY", stats => stats.count)
        printStrideStats(
          s"$name STRIDES MAX SIZE SUMMARY",
          stats => stats.maxSize
        )
        printStrideStats(
          s"$name STRIDES AVERAGE SIZE SUMMARY",
          stats => Math.round(stats.totalSize.toFloat / stats.count.toFloat)
        )
      }
    }

    SchedulerResult(
      numberOfStages = 1,
      numberOfCombinedStages = 1,
      numberOfPartitions = 1,
      cycles = stats.aggregateCycles,
      energy = stats.aggregateEnergy,
      accumulatorUtilization = accumulatorUtilization,
      localUtilization = localUtilization,
      macs = macs,
      macEfficiency = macEfficiency,
    )
  }
}
