/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import scala.util.hashing.MurmurHash3

import tensil.tools.util
import tensil.tools.compiler.scheduler._
import tensil.{TablePrinter, TableLine}
import tensil.tools.CompilerException

class StandardScheduler(layerIndex: Int, context: StandardSchedulingContext)
    extends Scheduler(layerIndex, context) {

  override protected def doEmit(backend: Backend): SchedulerResult = {

    /** Root's stage signature is a combination of the address
      * value for the first stage const (the first weight vector)
      * and the hash of all address values for stage consts.
      */
    case class StageSignature(
        firstConstAddressValue: Option[MemoryAddressRaw],
        constAddressValuesHash: Int
    )

    def rootStageSignature(root: VarOutputNode) = {
      val constValues =
        inputsToLoad(traverseRoots(Seq(root)), _.inputReusableConsts)
          .map(_.raw)
      StageSignature(
        firstConstAddressValue = constValues.headOption,
        constAddressValuesHash = MurmurHash3.unorderedHash(constValues)
      )
    }

    val rootsByStages = roots.par
      .groupBy(rootStageSignature(_))
      .seq
      .toSeq
      .sortBy(_._1.firstConstAddressValue)
      .map(_._2.seq.toSeq)

    case class UsageInfo(
        accumulatorSize: Int,
        localSize: Int,
    )

    case class PartitionInfo(
        roots: Option[Seq[VarOutputNode]],
        usage: UsageInfo
    )

    case class CombinedStageInfo(
        reusableConsts: Seq[MemoryAddress],
        partitions: Seq[PartitionInfo]
    )

    def combineStages(
        order: Int = 0,
        prevStages: Option[Seq[CombinedStageInfo]] = None
    ): Seq[CombinedStageInfo] = {

      val size                       = 1 << order
      val combinationCandidateStages =
        /** We want to combine the stages in strides in order to
          * retain roll-up opportunities. For this we use interleave.
          *
          * For example, when we have stages (0,1,2,3,4,5,6,7) and size=2,
          * the group seqs will be (0,1), (2,3), (4,5), (6,7). Then,
          * interleaved, the combination candidates will be (0,2,4,6)
          * and (1,3,5,7).
          */
        util
          .interleave(
            rootsByStages
              .grouped(util.divCeil(rootsByStages.size, size))
              .toSeq
          )
          .par
          .map(combinationCandidateStagedRoots => {
            val reusableConsts = inputsToLoad(
              traverseRoots(combinationCandidateStagedRoots.map(_.head)),
              _.inputReusableConsts
            )

            def partitionRoots(
                roots: Seq[VarOutputNode],
                partitions: Seq[PartitionInfo] = Nil
            ): Seq[PartitionInfo] = {
              case class MaximumPartitionSizeInfo(
                  n: Option[Int],
                  usage: UsageInfo
              )

              def findNextMaximumPartitionSize(
                  roots: Seq[VarOutputNode],
                  n: Int = 1,
                  powerOfTwoPass: Boolean = true,
                  prevUsage: Option[UsageInfo] = None
              ): MaximumPartitionSizeInfo = {
                require(roots.size > 0)

                val nodes           = traverseRoots(roots.take(n))
                val accumulatorSize = estimateAccumulatorSize(nodes)
                val localSize =
                  estimateLocalSize(
                    nodes,
                    includeReusableConsts = false,
                    includeNonReusableConsts = true,
                    includeVars = true
                  ) + reusableConsts.size
                val usage = UsageInfo(
                  accumulatorSize = accumulatorSize,
                  localSize = localSize
                )

                if (
                  accumulatorSize > context.arch.accumulatorDepth || localSize > context.arch.threadLocalDepth
                ) {
                  if (n == 1)
                    /**
                      * When a single root is not fitting the memories
                      * return n as None and usage that is greater than
                      * depth.
                      */
                    MaximumPartitionSizeInfo(
                      n = None,
                      usage = usage
                    )
                  else {
                    if (powerOfTwoPass)
                      findNextMaximumPartitionSize(
                        roots,
                        (n / 2) + 1,
                        false,
                        prevUsage
                      )
                    else
                      MaximumPartitionSizeInfo(
                        n = Some(n - 1),
                        usage = prevUsage.get
                      )
                  }
                } else if (n >= roots.size)
                  MaximumPartitionSizeInfo(
                    n = Some(roots.size),
                    usage = usage
                  )
                else {
                  if (powerOfTwoPass)
                    findNextMaximumPartitionSize(
                      roots,
                      n * 2,
                      true,
                      Some(usage)
                    )
                  else
                    findNextMaximumPartitionSize(
                      roots,
                      n + 1,
                      false,
                      Some(usage)
                    )
                }
              }

              if (!roots.isEmpty) {
                findNextMaximumPartitionSize(roots) match {
                  case MaximumPartitionSizeInfo(None, usage) =>
                    /**
                      * When cannot find a partition that is fitting the
                      * memories return roots as None and usage that is
                      * greater than depth.
                      */
                    partitions :+ PartitionInfo(
                      roots = None,
                      usage = usage
                    )
                  case MaximumPartitionSizeInfo(Some(n), usage) =>
                    val (partition, rest) = roots.splitAt(n)

                    partitionRoots(
                      rest,
                      partitions :+ PartitionInfo(
                        roots = Some(partition),
                        usage = usage
                      )
                    )

                }
              } else partitions
            }

            val partitions =
              partitionRoots(
                combinationCandidateStagedRoots.flatten
                  .sortBy(_.output.raw)
              )

            CombinedStageInfo(reusableConsts, partitions)
          })
          .seq

      /**
        * Try finding the usage for at least one stage with
        * the last partition not fitting the memories.
        */
      combinationCandidateStages
        .map(_.partitions.last)
        .filter(!_.roots.isDefined)
        .map(_.usage)
        .headOption match {
        case Some(UsageInfo(accumulatorSize, localSize)) =>
          if (prevStages.isDefined)
            prevStages.get
          else {
            if (accumulatorSize > context.arch.accumulatorDepth)
              throw new CompilerException(
                s"Insufficient accumulators, required at least ${accumulatorSize} for a single root"
              )
            else
              throw new CompilerException(
                s"Insufficient local memory, required at least ${localSize} for a single root"
              )
          }

        case None =>
          /**
            * All stages are fitting the memories.
            *
            * Check if any of the stages has more than one partition.
            *
            * If so, stop the combination process and return previously
            * combined stages if available or the initial non-combined
            * stages otherwise.
            *
            * If otherwise, and there is still an opportunity for
            * combination try combining twice as many stages.
            */
          if (combinationCandidateStages.map(_.partitions.size).max > 1) {
            if (prevStages.isDefined)
              prevStages.get
            else combinationCandidateStages
          } else if (size < rootsByStages.size)
            combineStages(order + 1, Some(combinationCandidateStages))
          else
            combinationCandidateStages
      }
    }

    val name                   = s"LAYER $layerIndex"
    val numberOfStages         = rootsByStages.size
    val stages                 = combineStages()
    val numberOfCombinedStages = stages.size
    val partitions             = stages.map(_.partitions).flatten
    val numberOfPartitions     = partitions.size
    val maximumRootsSize       = partitions.map(_.roots.get.size).max
    val accumulatorSize        = partitions.map(_.usage.accumulatorSize).max
    val localSize              = partitions.map(_.usage.localSize).max

    val accumulatorUtilization =
      accumulatorSize.toFloat / context.arch.accumulatorDepth.toFloat
    val localUtilization = localSize.toFloat / context.arch.localDepth.toFloat

    val stats = new Stats()

    if (context.options.printProgress) {
      println(
        s"Planned ${numberOfCombinedStages} stage(s) and ${numberOfPartitions} partition(s)"
      )
      println(s"Emitting LIR ...")
    }

    val instructionsCounts = stages.zipWithIndex.par
      .map({
        case (stage, i) =>
          val localSpace =
            ArenaMemorySpace(
              "local",
              MemoryTag.Local,
              context.arch.threadLocalDepth
            )
          val initLocalAllocator = RenamingMemoryAllocator(localSpace)

          val initKey =
            BackendSegmentKey(layerIndex, i, 0, BackendSegmentKey.Init)
          val initStats = new Stats()
          val initSegment = backend.mkSegment(
            initKey,
            Some(initStats)
          )

          emitLoadMemory(
            initSegment.segmentLir,
            initLocalAllocator,
            stage.reusableConsts
          )

          initSegment.segmentLir.endEmit()

          val partitionSegmentAndStats = stage.partitions.zipWithIndex.par
            .map({
              case (partition, j) =>
                val partitionLocalAllocator = initLocalAllocator.clone()

                val loadKey = BackendSegmentKey(
                  layerIndex,
                  i,
                  j,
                  BackendSegmentKey.Load
                )
                val computeKey = BackendSegmentKey(
                  layerIndex,
                  i,
                  j,
                  BackendSegmentKey.Compute
                )
                val saveKey = BackendSegmentKey(
                  layerIndex,
                  i,
                  j,
                  BackendSegmentKey.Save
                )

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

                val nodes = traverseRoots(partition.roots.get)

                emitLoadConsts(
                  loadSegment.segmentLir,
                  partitionLocalAllocator,
                  nodes,
                  includeReusableConsts = false,
                  includeNonReusableConsts = true
                )

                emitLoadVars(
                  loadSegment.segmentLir,
                  partitionLocalAllocator,
                  nodes
                )

                emitCompute(
                  computeSegment.segmentLir,
                  partitionLocalAllocator,
                  partitionLocalAllocator,
                  nodes
                )

                emitSaveVars(
                  saveSegment.segmentLir,
                  partitionLocalAllocator,
                  nodes
                )

                loadSegment.segmentLir.endEmit()
                computeSegment.segmentLir.endEmit()
                saveSegment.segmentLir.endEmit()

                Seq(
                  (loadSegment, loadStats),
                  (computeSegment, computeStats),
                  (saveSegment, saveStats)
                )
            })
            .seq

          (initSegment, initStats, partitionSegmentAndStats)
      })
      .seq
      .map({
        case ((initSegment, initStats, partitionSegmentAndStats)) => {
          backend.emitSegment(initSegment)

          stats.add(initStats)

          val instructionsCounts =
            partitionSegmentAndStats.map(segmentAndStats => {
              segmentAndStats.foreach(v => stats.add(v._2))

              for ((partitionSegment, partitionStats) <- segmentAndStats)
                yield {
                  backend.emitSegment(partitionSegment)

                  partitionSegment.instructionsCount
                }
            })

          (initSegment.instructionsCount, instructionsCounts.flatten)
        }
      })

    if (context.options.printProgress) {
      println(
        s"Emitted ${instructionsCounts.map(pair => pair._1 + pair._2.sum).sum} instruction(s)"
      )
    }

    val stageInitInstructionCounts = instructionsCounts.map(_._1)
    val partitionInstructionCounts = instructionsCounts.map(_._2).flatten
    val macEfficiency              = Stats.macEfficiency(stats, context.arch, macs)

    if (context.options.printSchedulerSummary) {
      val tb = new TablePrinter(Some(s"$name SCHEDULER SUMMARY"))
      tb.addNamedLine("Stages", numberOfStages)
      tb.addNamedLine("Combined Stages", numberOfCombinedStages)
      tb.addNamedLine("Partitions", numberOfPartitions)
      tb.addNamedLine("Partition results size", maximumRootsSize)
      tb.addNamedLine("Partition accumulator size", accumulatorSize)
      tb.addNamedLine("Partition local size", localSize)
      tb.addNamedLine(
        "Stage init maximum number of instructions",
        stageInitInstructionCounts.max
      )
      tb.addNamedLine(
        "Stage init average number of instructions",
        stageInitInstructionCounts.sum / stageInitInstructionCounts.size
      )
      tb.addNamedLine(
        "Partition maximum number of instructions",
        partitionInstructionCounts.max
      )
      tb.addNamedLine(
        "Partition average number of instructions",
        partitionInstructionCounts.sum / partitionInstructionCounts.size
      )
      Stats.printSummary(stats, tb, context.arch, Some(macs))
      tb.addNamedLine(
        "Total number of instructions",
        stageInitInstructionCounts.sum + partitionInstructionCounts.sum
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
      numberOfStages = numberOfStages,
      numberOfCombinedStages = numberOfCombinedStages,
      numberOfPartitions = numberOfPartitions,
      cycles = stats.aggregateCycles,
      energy = stats.aggregateEnergy,
      accumulatorUtilization = accumulatorUtilization,
      localUtilization = localUtilization,
      macs = macs,
      macEfficiency = macEfficiency,
    )
  }
}
