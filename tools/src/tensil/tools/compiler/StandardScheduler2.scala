/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import tensil.TablePrinter

class StandardScheduler2(layerIndex: Int, context: StandardSchedulingContext)
    extends Scheduler(layerIndex, context) {

  override protected def doEmit(backend: Backend): SchedulerResult = {
    val nodes = traverseRoots(roots)

    val accumulatorSize = estimateAccumulatorSize(nodes)
    val localSize       = estimateLocalSize(nodes)

    val name             = s"LAYER $layerIndex"
    val maximumRootsSize = roots.size

    val accumulatorUtilization =
      accumulatorSize.toFloat / context.arch.accumulatorDepth.toFloat
    val localUtilization = localSize.toFloat / context.arch.localDepth.toFloat

    val stats = new Stats()

    if (context.options.printProgress) {
      println(s"Emitting LIR ...")
    }

    val initKey =
      BackendSegmentKey(layerIndex, 0, 0, BackendSegmentKey.Init)
    val initStats = new Stats()
    val initSegment = backend.mkSegment(
      initKey,
      Some(initStats)
    )

    val localSpace =
      HeapMemorySpace("local", MemoryTag.Local, context.arch.threadLocalDepth)
    val previousLocalAllocator =
      RenamingMemoryAllocator(localSpace, Set(MemoryTag.Consts, MemoryTag.Vars))
    val nextLocalAllocator =
      RenamingMemoryAllocator(localSpace, Set(MemoryTag.Consts, MemoryTag.Vars))

    val loadKey = BackendSegmentKey(layerIndex, 0, 0, BackendSegmentKey.Load)
    val computeKey =
      BackendSegmentKey(layerIndex, 0, 0, BackendSegmentKey.Compute)
    val saveKey = BackendSegmentKey(layerIndex, 0, 0, BackendSegmentKey.Save)

    val (loadStats, computeStats, saveStats) =
      (new Stats(), new Stats(), new Stats())

    val (loadSegment, computeSegment, saveSegment) =
      (
        backend.mkSegment(
          loadKey,
          Some(loadStats)
        ),
        backend.mkSegment(
          computeKey,
          Some(computeStats)
        ),
        backend.mkSegment(
          saveKey,
          Some(saveStats)
        )
      )

    emitLoadConsts(
      initSegment.segmentLir,
      previousLocalAllocator,
      nodes
    )

    emitLoadVars(
      loadSegment.segmentLir,
      previousLocalAllocator,
      nodes
    )

    emitCompute(
      computeSegment.segmentLir,
      previousLocalAllocator,
      nextLocalAllocator,
      nodes
    )

    emitSaveVars(
      saveSegment.segmentLir,
      nextLocalAllocator,
      nodes
    )

    initSegment.segmentLir.endEmit()
    loadSegment.segmentLir.endEmit()
    computeSegment.segmentLir.endEmit()
    saveSegment.segmentLir.endEmit()

    backend.emitSegment(initSegment)
    backend.emitSegment(loadSegment)
    backend.emitSegment(computeSegment)
    backend.emitSegment(saveSegment)

    stats.add(initStats)
    stats.add(loadStats)
    stats.add(computeStats)
    stats.add(saveStats)

    val instructionsCount =
      initSegment.instructionsCount + loadSegment.instructionsCount + computeSegment.instructionsCount + saveSegment.instructionsCount

    if (context.options.printProgress) {
      println(
        s"Emitted ${instructionsCount} instruction(s)"
      )
    }

    val macEfficiency = Stats.macEfficiency(stats, context.arch, macs)

    if (context.options.printSchedulerSummary) {
      val tb = new TablePrinter(Some(s"$name SCHEDULER SUMMARY"))
      tb.addNamedLine("Partition results size", maximumRootsSize)
      tb.addNamedLine("Partition accumulator size", accumulatorSize)
      tb.addNamedLine("Partition local size", localSize)
      Stats.printSummary(stats, tb, context.arch, Some(macs))
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
            context.arch.stride0Depth,
            context.arch.stride1Depth,
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
