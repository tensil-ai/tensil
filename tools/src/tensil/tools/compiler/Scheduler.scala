/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io.{
  FileOutputStream,
  ObjectOutputStream,
  FileInputStream,
  ObjectInputStream,
  EOFException
}

import scala.collection.mutable
import scala.util.hashing.MurmurHash3

import _root_.tensil.tools.{
  CompilerException,
  CompilerOptions,
  TraceContext,
  TracepointCondition,
}
import _root_.tensil.tools.compiler.scheduler._
import _root_.tensil.tools.util
import _root_.tensil.{TablePrinter, TableLine, Architecture}
import java.io.DataOutputStream

case class SchedulerResult(
    numberOfCombinedStages: Int,
    numberOfStages: Int,
    numberOfPartitions: Int,
    cycles: Long,
    energy: Long,
    accumulatorUtilization: Float,
    localUtilization: Float,
    macs: Long,
    macEfficiency: Float
) {}

class Scheduler(
    layerIndex: Int,
    arch: Architecture,
    options: CompilerOptions
) extends HIR {
  if (options.printProgress) {
    println(s"LAYER $layerIndex, emitting HIR ...")
  }

  private var tempOutputNodes =
    mutable.Map.empty[MemoryAddress, TempOutputNode]
  private var varOutputNodes = mutable.Map.empty[MemoryAddress, VarOutputNode]

  private var macs = 0L

  private def countMacs(
      weightsObj: MemoryObject,
      inputOutputPairs: Seq[MemoryOptionalInputOutputObjects]
  ): Unit =
    for (pair <- inputOutputPairs)
      if (pair.input.isDefined)
        macs += (weightsObj.dims.width * weightsObj.dims.height * pair.input.get.dims.number)

  def emitMatMul(
      weightsObj: MemoryObject,
      biasObj: Option[MemoryObject],
      inputOutputPairs: Seq[MemoryOptionalInputOutputObjects]
  ): Unit = {
    countMacs(weightsObj, inputOutputPairs)

    if (
      biasObj.isDefined && weightsObj.dims.widthVectors != biasObj.get.dims.widthVectors
    )
      throw new CompilerException(
        "Weights width must match bias width"
      )

    val weightsHeight =
      util.divCeil(weightsObj.dims.heightVectors, arch.arraySize)

    for (pair <- inputOutputPairs) {
      if (
        pair.input.isDefined && pair.input.get.dims.heightVectors != weightsHeight
      )
        throw new CompilerException(
          "Weights height must match input height"
        )

      if (pair.output.dims.heightVectors != weightsObj.dims.widthVectors)
        throw new CompilerException(
          "Output height must match weights width"
        )

      if (
        pair.input.isDefined && (pair.output.dims.numberVectors != pair.input.get.dims.numberVectors)
      )
        throw new CompilerException(
          "Output number must match input number"
        )
    }

    def mkWeights(obj: MemoryObject, offset: Int) = obj

    val outputMatMulInputs =
      for (
        i <- 0 until weightsObj.dims.widthVectors;
        j <- 0 until weightsHeight
      ) yield {
        val bias = if (biasObj.isDefined && j == weightsHeight - 1) {
          biasObj.get.mkAddress(i)
        } else {
          MemoryAddress.Zeroes
        }

        val weights =
          for (k <- 0 until arch.arraySize)
            yield weightsObj.mkAddress(
              i + (k + j * arch.arraySize) * weightsObj.dims.widthVectors
            )

        val weightsAndBias = weights.reverse :+ bias

        for (
          pair <- inputOutputPairs;
          n    <- 0 until pair.output.dims.numberVectors
        ) yield {
          val output =
            pair.output.mkAddress(i + n * pair.output.dims.heightVectors)

          val input =
            if (pair.input.isDefined)
              pair.input.get
                .mkAddress(j + n * pair.input.get.dims.heightVectors)
            else
              MemoryAddress.Zeroes

          val matMulInput = MatMulInput(
            weightsAndBias.toVector,
            input
          )

          (output, matMulInput)
        }
      }

    for (
      (output, matMulInputs) <- outputMatMulInputs.flatten.groupBy(_._1).map {
        case (output, g) => (output, g.map(_._2))
      }
    ) {
      val matMulNode = tempOutputNodes
        .getOrElseUpdate(
          output,
          new MatMulNode(Vector.empty[MatMulInput], output)
        )
        .asInstanceOf[MatMulNode]

      tempOutputNodes(output) =
        new MatMulNode(matMulNode.inputs ++ matMulInputs, output)
    }
  }

  def emitLoad(
      inputObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {
    require(inputObj.dims.sizeVectors == outputObj.dims.sizeVectors)
    for (i <- 0 until outputObj.dims.sizeVectors) {
      val output = outputObj.mkAddress(i)
      require(!tempOutputNodes.contains(output))
      tempOutputNodes(output) = new LoadNode(
        inputObj.mkAddress(i),
        output
      )
    }
  }

  def emitAdd(
      input0Obj: MemoryObject,
      input1Obj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {
    require(input0Obj.dims.sizeVectors == outputObj.dims.sizeVectors)
    require(input1Obj.dims.sizeVectors == outputObj.dims.sizeVectors)
    for (i <- 0 until outputObj.dims.sizeVectors) {
      val output = outputObj.mkAddress(i)
      require(!tempOutputNodes.contains(output))
      tempOutputNodes(output) = new AddNode(
        input0Obj.mkAddress(i),
        input1Obj.mkAddress(i),
        output
      )
    }
  }

  def emitRelu(
      inputObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {
    require(inputObj.dims.sizeVectors == outputObj.dims.sizeVectors)
    for (i <- 0 until outputObj.dims.sizeVectors) {
      val output = outputObj.mkAddress(i)
      require(!tempOutputNodes.contains(output))
      tempOutputNodes(output) = new ReluNode(
        inputObj.mkAddress(i),
        output
      )
    }
  }

  def emitSoftmax(
      inputObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {
    require(inputObj.dims.sizeVectors == outputObj.dims.sizeVectors)
    for (i <- 0 until outputObj.dims.sizeVectors) {
      val output = outputObj.mkAddress(i)
      require(!tempOutputNodes.contains(output))
      tempOutputNodes(output) = new SoftmaxNode(
        inputObj.mkAddress(i),
        output
      )
    }
  }

  def emitLeakyRelu(
      inputObj: MemoryObject,
      alphaObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {
    require(inputObj.dims.sizeVectors == outputObj.dims.sizeVectors)
    require(alphaObj.dims.sizeVectors == 1)
    for (i <- 0 until outputObj.dims.sizeVectors) {
      val output = outputObj.mkAddress(i)
      require(!tempOutputNodes.contains(output))
      tempOutputNodes(output) = new LeakyReluNode(
        alphaObj.mkAddress(0),
        inputObj.mkAddress(i),
        output
      )
    }
  }

  def emitPool(
      op: String,
      inputObjs: Seq[MemoryObject],
      outputObj: MemoryObject,
      multiplierObj: Option[MemoryObject]
  ): Unit = {
    require(inputObjs.forall(_.dims.sizeVectors == outputObj.dims.sizeVectors))
    require(!multiplierObj.isDefined || multiplierObj.get.dims.sizeVectors == 1)
    for (i <- 0 until outputObj.dims.sizeVectors) {
      val output = outputObj.mkAddress(i)
      require(!tempOutputNodes.contains(output))
      val node = tempOutputNodes(output) = new PoolNode(
        op,
        output,
        inputObjs.map(_.mkAddress(i)).toVector,
        multiplierObj.map(_.mkAddress(0))
      )
    }
  }

  def emitNorm(
      inputObj: MemoryObject,
      scaleObj: MemoryObject,
      offsetObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {
    require(inputObj.dims.sizeVectors == outputObj.dims.sizeVectors)
    require(scaleObj.dims.sizeVectors == outputObj.dims.sizeVectors)
    require(offsetObj.dims.sizeVectors == outputObj.dims.sizeVectors)
    for (i <- 0 until outputObj.dims.sizeVectors) {
      val output = outputObj.mkAddress(i)
      require(!tempOutputNodes.contains(output))
      tempOutputNodes(output) = new NormNode(
        inputObj.mkAddress(i),
        scaleObj.mkAddress(i),
        offsetObj.mkAddress(i),
        output
      )
    }
  }

  def emitInterpolate(
      inputObjs: Seq[MemoryObject],
      scaleObjs: Seq[MemoryObject],
      outputObj: MemoryObject
  ): Unit = {
    require(inputObjs.forall(_.dims.sizeVectors == outputObj.dims.sizeVectors))
    require(scaleObjs.forall(_.dims.sizeVectors == outputObj.dims.sizeVectors))
    for (i <- 0 until outputObj.dims.sizeVectors) {
      val output = outputObj.mkAddress(i)
      require(!tempOutputNodes.contains(output))
      tempOutputNodes(output) = new InterpolateNode(
        output,
        inputObjs.map(_.mkAddress(i)).toVector,
        scaleObjs.map(_.mkAddress(i)).toVector
      )
    }
  }

  def emitSave(
      inputObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {
    require(inputObj.dims.sizeVectors == outputObj.dims.sizeVectors)
    for (i <- 0 until outputObj.dims.sizeVectors) {
      val output = outputObj.mkAddress(i)
      require(!tempOutputNodes.contains(output))
      varOutputNodes(output) = new SaveNode(
        inputObj.mkAddress(i),
        output
      )
    }
  }

  private def inputsToLoad(
      nodes: Seq[Node],
      inputs: Node => Seq[MemoryAddress]
  ): Seq[MemoryAddress] = {
    val nodesWithInputsToLoadCount =
      nodes.filter(!inputs(_).isEmpty).size
    val maxInputsToLoadCount = nodes.map(inputs(_).size).max

    /*
     * In order to improve rollup efficiency we need to determine
     * the best sorting order for inputs loaded into local memory.
     *
     * If number of nodes with inputs is greater than maximum
     * number of inputs per node, we sort the entire set of inputs.
     * This will likely produce one rollup per memory tag.
     *
     * If otherwise, we sort inputs for each node. This will likely
     * produce one rollup per node per memory tag.
     */

    def distinctByLocation(
        addresses: Seq[MemoryAddress]
    ): Seq[MemoryAddress] = {
      val set = mutable.Set.empty[MemoryAddress]

      for (address <- addresses if !set.contains(address)) yield {
        set.add(address)
        address
      }
    }

    if (nodesWithInputsToLoadCount > maxInputsToLoadCount)
      distinctByLocation(
        nodes
          .flatMap(inputs(_))
          .sorted
      )
    else
      distinctByLocation(
        nodes
          .map(inputs(_))
          .filter(_.nonEmpty)
          .sortBy(_.min)
          .flatMap(_.sorted)
      )
  }

  def saveGraph(fileName: String): Unit = {
    val stream = new ObjectOutputStream(new FileOutputStream(fileName))

    for (node <- varOutputNodes.values)
      stream.writeObject(node)

    for (node <- tempOutputNodes.values)
      stream.writeObject(node)

    stream.close()
  }

  def loadGraph(fileName: String): Unit = {
    val stream = new ObjectInputStream(new FileInputStream(fileName))

    try {
      while (true) {
        val node = stream.readObject()

        if (node.isInstanceOf[VarOutputNode]) {
          val varOutputNode = node.asInstanceOf[VarOutputNode]
          varOutputNodes(varOutputNode.output) = varOutputNode
        } else if (node.isInstanceOf[TempOutputNode]) {
          val tempOutputNode = node.asInstanceOf[TempOutputNode]
          tempOutputNodes(tempOutputNode.output) = tempOutputNode
        }
      }
    } catch {
      case _: EOFException =>
    }

    stream.close()
  }

  def emit(
      backend: Backend,
      backendBufferSize: Int = 256 * 1024
  ): SchedulerResult = {
    if (options.printProgress) {
      println(
        s"Emitted ${varOutputNodes.size} root and ${tempOutputNodes.size} non-root node(s)"
      )
      println(s"Planning stages and partition ...")
    }

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
        inputsToLoad(traverseRoots(Seq(root)), _.inputStageConsts)
          .map(_.raw)
      StageSignature(
        firstConstAddressValue = constValues.headOption,
        constAddressValuesHash = MurmurHash3.unorderedHash(constValues)
      )
    }

    val rootsByStages = varOutputNodes.values.par
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
        constsToLoad: Seq[MemoryAddress],
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
            val consts = inputsToLoad(
              traverseRoots(combinationCandidateStagedRoots.map(_.head)),
              _.inputStageConsts
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
                val accumulatorSize = estimatePartitionAccumulatorSize(nodes)
                val localSize =
                  estimatePartitionLocalSize(nodes) + consts.size
                val usage = UsageInfo(
                  accumulatorSize = accumulatorSize,
                  localSize = localSize
                )

                if (
                  accumulatorSize > arch.accumulatorDepth || localSize > arch.threadLocalDepth
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

            CombinedStageInfo(consts, partitions)
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
            if (accumulatorSize > arch.accumulatorDepth)
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
      accumulatorSize.toFloat / arch.accumulatorDepth.toFloat
    val localUtilization = localSize.toFloat / arch.localDepth.toFloat

    val stats = new Stats()

    if (options.printProgress) {
      println(
        s"Planned ${numberOfCombinedStages} stage(s) and ${numberOfPartitions} partition(s)"
      )
      println(s"Emitting LIR ...")
    }

    val instructionsCounts = stages.zipWithIndex.par
      .map({
        case (stage, i) =>
          val initKey =
            BackendSegmentKey(layerIndex, i, 0, BackendSegmentKey.Init)
          val initStats = new Stats()
          val initSegment = backend.mkSegment(
            initKey,
            Some(initStats)
          )
          val stageInit =
            emitStageInit(initSegment.segmentLir, stage.constsToLoad)

          val partitionSegmentAndStats = stage.partitions.zipWithIndex.par
            .map({
              case (partition, j) =>
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

                emitStagePartition(
                  loadSegment.segmentLir,
                  computeSegment.segmentLir,
                  saveSegment.segmentLir,
                  stageInit,
                  traverseRoots(partition.roots.get)
                )

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

    if (options.printProgress) {
      println(
        s"Emitted ${instructionsCounts.map(pair => pair._1 + pair._2.sum).sum} instruction(s)"
      )
    }

    val stageInitInstructionCounts = instructionsCounts.map(_._1)
    val partitionInstructionCounts = instructionsCounts.map(_._2).flatten
    val macEfficiency              = Stats.macEfficiency(stats, arch, macs)

    if (options.printSchedulerSummary) {
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
      Stats.printSummary(stats, tb, arch, Some(macs))
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

      if (options.printInstructionsSummary) {
        Stats.printCompositionSummary(name, stats)
        Stats.printCyclesSummary(name, stats)
        Stats.printEnergySummary(name, stats)
      }

      if (options.printStridesSummary) {
        def printStrideStats(
            title: String,
            select: StrideStats => Any
        ): Unit = {
          val tb = new TablePrinter(Some(title), true)
          Stats.printStrideStats(
            arch.stride0Depth,
            arch.stride1Depth,
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

  private def traverseRoots(
      roots: Seq[VarOutputNode]
  ): Seq[Node] = {
    val traversedNodes = mutable.Map.empty[MemoryAddressRaw, Node]

    for (root <- roots) {
      def traverseNodes(node: Node) {
        for (temp <- node.inputTemps) {
          val node = tempOutputNodes(temp)
          traversedNodes(temp.raw) = node
          traverseNodes(node)
        }
      }

      traverseNodes(root)
    }

    traversedNodes.values.toSeq ++ roots.asInstanceOf[Seq[Node]]
  }

  private def estimatePartitionAccumulatorSize(
      nodes: Seq[Node]
  ): Int = {
    val unique = mutable.Set.empty[MemoryAddressRaw]

    for (
      node <-
        nodes
          .filter(_.isInstanceOf[TempOutputNode])
          .map(_.asInstanceOf[TempOutputNode])
    )
      unique += node.output.raw

    unique.size
  }

  private def estimatePartitionLocalSize(
      nodes: Seq[Node]
  ): Int = {
    val unique = mutable.Set.empty[MemoryAddress]

    for (
      node <-
        nodes
          .filter(_.isInstanceOf[TempOutputNode])
          .map(_.asInstanceOf[TempOutputNode])
    ) {
      unique ++= node.inputVars
      unique ++= node.inputPartitionConsts
    }

    for (
      node <-
        nodes
          .filter(_.isInstanceOf[VarOutputNode])
          .map(_.asInstanceOf[VarOutputNode])
    )
      unique += node.output

    unique.size
  }

  private case class StageInitInfo(
      constsToLocalRenameMap: Map[MemoryAddressRaw, MemoryAddressRaw]
  )

  private def emitStageInit(
      lir: LIR,
      constsToLoad: Seq[MemoryAddress]
  ): StageInitInfo = {
    var toLocalRenameNext: MemoryAddressRaw = 0
    val constsToLocalRenameMap =
      mutable.Map.empty[MemoryAddressRaw, MemoryAddressRaw]

    val loadLocalRollup = new DoubleAddressRollup(
      lir.emitDataMove(toLocal = true, accumulate = false, _, _, _, _, _),
      arch
    )

    for (constAddress <- constsToLoad) {
      require(constAddress.tag == MemoryTag.Consts)
      require(!constsToLocalRenameMap.contains(constAddress.raw))

      val localAddressValue = toLocalRenameNext
      toLocalRenameNext += 1
      constsToLocalRenameMap(constAddress.raw) = localAddressValue

      loadLocalRollup.emit(
        MemoryAddress(MemoryTag.Local, constAddress.ref, localAddressValue),
        constAddress
      )
    }

    loadLocalRollup.finalEmit()
    lir.endEmit()

    StageInitInfo(constsToLocalRenameMap = constsToLocalRenameMap.toMap)
  }

  private def emitStagePartition(
      loadLir: LIR,
      computeLir: LIR,
      saveLir: LIR,
      stage: StageInitInfo,
      nodes: Seq[Node]
  ): Unit = {
    var accumulatorRenameNext: MemoryAddressRaw = 0L;
    val accumulatorRenameMap =
      mutable.Map.empty[MemoryAddressRaw, MemoryAddressRaw]

    def allocateAccumulator(
        tempAddress: MemoryAddress
    ): MemoryAddress = {
      require(tempAddress.tag == MemoryTag.Temp)

      require(!accumulatorRenameMap.contains(tempAddress.raw))

      val accAddressValue = accumulatorRenameNext
      accumulatorRenameNext += 1
      accumulatorRenameMap(tempAddress.raw) = accAddressValue
      MemoryAddress(MemoryTag.Accumulators, tempAddress.ref, accAddressValue)
    }

    def locateAccumulator(
        tempAddress: MemoryAddress
    ): MemoryAddress = {
      require(tempAddress.tag == MemoryTag.Temp)

      MemoryAddress(
        MemoryTag.Accumulators,
        tempAddress.ref,
        accumulatorRenameMap(tempAddress.raw)
      )
    }

    var toLocalRenameNext: MemoryAddressRaw =
      if (stage.constsToLocalRenameMap.isEmpty) 0
      else stage.constsToLocalRenameMap.values.max + 1;
    val varsToLocalRenameMap =
      mutable.Map.empty[MemoryAddressRaw, MemoryAddressRaw]
    val constsToLocalRenameMap =
      mutable.Map.empty[
        MemoryAddressRaw,
        MemoryAddressRaw
      ] ++ stage.constsToLocalRenameMap

    def allocateLocal(varsOrConstsAddress: MemoryAddress): MemoryAddress = {
      require(
        varsOrConstsAddress.tag == MemoryTag.Vars || varsOrConstsAddress.tag == MemoryTag.Consts
      )

      val map = varsOrConstsAddress.tag match {
        case MemoryTag.Vars   => varsToLocalRenameMap
        case MemoryTag.Consts => constsToLocalRenameMap
      }

      require(!map.contains(varsOrConstsAddress.raw))

      val localAddressValue = toLocalRenameNext
      toLocalRenameNext += 1
      map(varsOrConstsAddress.raw) = localAddressValue

      MemoryAddress(
        MemoryTag.Local,
        varsOrConstsAddress.ref,
        localAddressValue
      ),
    }

    def locateLocal(varsOrConstsAddress: MemoryAddress): MemoryAddress = {
      require(
        varsOrConstsAddress.tag == MemoryTag.Vars || varsOrConstsAddress.tag == MemoryTag.Consts
      )

      val map = varsOrConstsAddress.tag match {
        case MemoryTag.Vars   => varsToLocalRenameMap
        case MemoryTag.Consts => constsToLocalRenameMap
      }

      MemoryAddress(
        MemoryTag.Local,
        varsOrConstsAddress.ref,
        map(varsOrConstsAddress.raw)
      )
    }

    val loadLocalRollup = new DoubleAddressRollup(
      loadLir
        .emitDataMove(toLocal = true, accumulate = false, _, _, _, _, _),
      arch
    )

    for (constAddress <- inputsToLoad(nodes, _.inputPartitionConsts))
      loadLocalRollup.emit(
        allocateLocal(constAddress),
        constAddress
      )

    for (varsAddress <- inputsToLoad(nodes, _.inputVars))
      loadLocalRollup.emit(
        allocateLocal(varsAddress),
        varsAddress
      )

    loadLocalRollup.finalEmit()

    val groupedMatMulInputOutputs =
      mutable.Map.empty[Int, mutable.ArrayBuffer[
        (MemoryAddress, MemoryAddress)
      ]]

    val groupedZeroesMatMulInputOutputs =
      mutable.Map.empty[Int, mutable.ArrayBuffer[MemoryAddress]]

    val groupedMatMulWeights =
      mutable.Map.empty[Int, Seq[MemoryAddress]]

    def weightsHash(input: Seq[MemoryAddress]) =
      MurmurHash3.orderedHash(
        input
          .map(a => if (a.tag == MemoryTag.Zeroes) -1L else a.raw)
      )

    for (
      mmNode <-
        nodes
          .filter(_.isInstanceOf[MatMulNode])
          .map(_.asInstanceOf[MatMulNode])
          .sortBy(_.output)
    ) {
      val outputAccAddress = allocateAccumulator(mmNode.output)

      for (input <- mmNode.inputs) {

        val key = weightsHash(input.weights)
        groupedMatMulWeights.getOrElseUpdate(key, input.weights)

        if (input.input.tag == MemoryTag.Zeroes) {
          val group = groupedZeroesMatMulInputOutputs.getOrElseUpdate(
            key,
            mutable.ArrayBuffer.empty[MemoryAddress]
          )

          group += (
            (
              outputAccAddress
            )
          )
        } else {
          val group = groupedMatMulInputOutputs.getOrElseUpdate(
            key,
            mutable.ArrayBuffer.empty[(MemoryAddress, MemoryAddress)]
          )

          group += (
            (
              locateLocal(input.input),
              outputAccAddress
            )
          )
        }
      }
    }

    val addressValuesToAccumulate = mutable.Set.empty[MemoryAddressRaw]

    def shouldAccumulate(address: MemoryAddress) =
      !addressValuesToAccumulate.add(address.raw)

    for (
      (weightsKey, weightsSeq) <- groupedMatMulWeights.toSeq.sortBy(_._2.head)
    ) {
      val loadWeightsRollup = new SingleAddressReverseRollup(
        computeLir.emitLoadWeights(_, _, _),
        arch
      )

      for (weights <- weightsSeq) {
        val weightsLocalAddress =
          if (weights.tag != MemoryTag.Zeroes)
            locateLocal(weights)
          else weights
        loadWeightsRollup.emit(weightsLocalAddress)
      }

      loadWeightsRollup.finalEmit()

      val matMulRollup = new DoubleAddressRollup(
        computeLir.emitMatMul(accumulate = false, _, _, _, _, _),
        arch
      )
      val matMulAccumulateRollup = new DoubleAddressRollup(
        computeLir.emitMatMul(accumulate = true, _, _, _, _, _),
        arch
      )

      groupedMatMulInputOutputs.get(weightsKey) match {
        case Some(group) =>
          for ((inputLocalAddress, outputAccAddress) <- group.sortBy(_._2)) {
            var rollup =
              if (shouldAccumulate(outputAccAddress))
                matMulAccumulateRollup
              else
                matMulRollup

            rollup.emit(
              inputLocalAddress,
              outputAccAddress
            )
          }

        case None =>
      }

      groupedZeroesMatMulInputOutputs.get(weightsKey) match {
        case Some(group) =>
          for (outputAccAddress <- group.sorted) {
            var rollup =
              if (shouldAccumulate(outputAccAddress))
                matMulAccumulateRollup
              else
                matMulRollup

            rollup.emit(
              MemoryAddress.Zeroes,
              outputAccAddress
            )
          }

        case None =>
      }

      matMulRollup.finalEmit()
      matMulAccumulateRollup.finalEmit()
    }

    val loadAccRollup = new DoubleAddressRollup(
      computeLir
        .emitDataMove(toLocal = false, accumulate = false, _, _, _, _, _),
      arch
    )

    val loadAccInputOutputs = nodes
      .filter(_.isInstanceOf[LoadNode])
      .map(_.asInstanceOf[LoadNode])
      .map(n => (locateLocal(n.input), n.output))

    for (
      (inputLocalAddress, outputTempAddress) <- loadAccInputOutputs.sortBy(_._1)
    ) {
      val outputAccAddress = allocateAccumulator(outputTempAddress)

      loadAccRollup.emit(
        inputLocalAddress,
        outputAccAddress
      )
    }

    loadAccRollup.finalEmit()

    for (
      addNode <-
        nodes
          .filter(_.isInstanceOf[AddNode])
          .map(_.asInstanceOf[AddNode])
          .sortBy(_.output)
    ) {
      val outputAccAddress = allocateAccumulator(addNode.output)
      val inputAccAddress  = locateAccumulator(addNode.input0)

      computeLir.emitSIMD(
        accumulate = false,
        SIMDOp.Move,
        SIMDSource.Input,
        SIMDSource.Input,
        SIMDDestination.Output,
        outputAccAddress,
        inputAccAddress
      )
    }

    val addRollup = new DoubleAddressRollup(
      computeLir
        .emitDataMove(toLocal = false, accumulate = true, _, _, _, _, _),
      arch
    )

    for (
      addNode <-
        nodes
          .filter(_.isInstanceOf[AddNode])
          .map(_.asInstanceOf[AddNode])
          .sortBy(_.output)
    ) {
      val outputAccAddress   = locateAccumulator(addNode.output)
      val input1LocalAddress = locateLocal(addNode.input1)

      addRollup.emit(
        input1LocalAddress,
        outputAccAddress,
      )
    }

    addRollup.finalEmit()

    for (
      normNode <-
        nodes
          .filter(_.isInstanceOf[NormNode])
          .map(_.asInstanceOf[NormNode])
          .sortBy(_.output)
    ) {
      val outputAccAddress = allocateAccumulator(normNode.output)
      val scaleAccAddress  = locateAccumulator(normNode.scale)
      val offsetAccAddress = locateAccumulator(normNode.offset)
      val inputAccAddress  = locateAccumulator(normNode.input)

      computeLir.emitSIMD(
        accumulate = false,
        SIMDOp.Move,
        SIMDSource.Input,
        0,
        SIMDDestination.Register1,
        MemoryAddress.Invalid,
        inputAccAddress
      )

      computeLir.emitSIMD(
        accumulate = false,
        SIMDOp.Multiply,
        SIMDSource.Input,
        SIMDSource.Register1,
        SIMDDestination.Register1,
        MemoryAddress.Invalid,
        scaleAccAddress
      )

      computeLir.emitSIMD(
        accumulate = false,
        SIMDOp.Add,
        SIMDSource.Input,
        SIMDSource.Register1,
        SIMDDestination.Output,
        outputAccAddress,
        offsetAccAddress
      )
    }

    for (
      interpolateNode <-
        nodes
          .filter(_.isInstanceOf[InterpolateNode])
          .map(_.asInstanceOf[InterpolateNode])
          .sortBy(_.output)
    ) {
      val outputAccAddress = allocateAccumulator(interpolateNode.output)

      for (i <- 0 until interpolateNode.scales.size) {
        val scaleAccAddress = locateAccumulator(interpolateNode.scales(i))
        val inputAccAddress = locateAccumulator(interpolateNode.inputs(i))

        computeLir.emitSIMD(
          accumulate = false,
          SIMDOp.Move,
          SIMDSource.Input,
          0,
          SIMDDestination.Register1,
          MemoryAddress.Invalid,
          inputAccAddress
        )

        computeLir.emitSIMD(
          accumulate = (i != 0),
          SIMDOp.Multiply,
          SIMDSource.Input,
          SIMDSource.Register1,
          SIMDDestination.Output,
          outputAccAddress,
          scaleAccAddress
        )
      }
    }

    var reluInited = false

    for (
      reluNode <-
        nodes
          .filter(_.isInstanceOf[ReluNode])
          .map(_.asInstanceOf[ReluNode])
          .sortBy(_.output)
    ) {
      val outputAccAddress = allocateAccumulator(reluNode.output)
      val inputAccAddress  = locateAccumulator(reluNode.input)

      if (!reluInited) {
        reluInited = true
        computeLir.emitSIMD(
          accumulate = false,
          SIMDOp.Zero,
          0,
          0,
          SIMDDestination.Register1,
          MemoryAddress.Invalid,
          MemoryAddress.Invalid
        )
      }

      computeLir.emitSIMD(
        accumulate = false,
        SIMDOp.Max,
        SIMDSource.Input,
        SIMDSource.Register1,
        SIMDDestination.Output,
        outputAccAddress,
        inputAccAddress
      )
    }

    for (
      softmaxNode <-
        nodes
          .filter(_.isInstanceOf[SoftmaxNode])
          .map(_.asInstanceOf[SoftmaxNode])
          .sortBy(_.output)
    ) {
      val outputAccAddress = allocateAccumulator(softmaxNode.output)
      val inputAccAddress  = locateAccumulator(softmaxNode.input)

      // TODO: Implement Softmax
      computeLir.emitSIMD(
        accumulate = false,
        SIMDOp.Move,
        SIMDSource.Input,
        SIMDSource.Input,
        SIMDDestination.Output,
        outputAccAddress,
        inputAccAddress
      )
    }

    for (
      leakyReluNode <-
        nodes
          .filter(_.isInstanceOf[LeakyReluNode])
          .map(_.asInstanceOf[LeakyReluNode])
          .sortBy(_.output)
    ) {
      val outputAccAddress = allocateAccumulator(leakyReluNode.output)
      val alphaAccAddress  = locateAccumulator(leakyReluNode.alpha)
      val inputAccAddress  = locateAccumulator(leakyReluNode.input)

      computeLir.emitSIMD(
        accumulate = false,
        SIMDOp.Move,
        SIMDSource.Input,
        0,
        SIMDDestination.Register1,
        MemoryAddress.Invalid,
        alphaAccAddress
      )

      computeLir.emitSIMD(
        accumulate = false,
        SIMDOp.Multiply,
        SIMDSource.Input,
        SIMDSource.Register1,
        SIMDDestination.Register1,
        MemoryAddress.Invalid,
        inputAccAddress
      )

      computeLir.emitSIMD(
        accumulate = false,
        SIMDOp.Max,
        SIMDSource.Input,
        SIMDSource.Register1,
        SIMDDestination.Output,
        outputAccAddress,
        inputAccAddress
      )
    }

    for (
      poolNode <-
        nodes
          .filter(_.isInstanceOf[PoolNode])
          .map(_.asInstanceOf[PoolNode])
          .sortBy(_.output)
    ) {
      val outputAccAddress = allocateAccumulator(poolNode.output)

      for (i <- 0 until poolNode.inputs.length) {
        val inputAccAddress = locateAccumulator(poolNode.inputs(i))

        val first = i == 0
        val last  = i == poolNode.inputs.length - 1

        if (poolNode.op == "MaxPool") {
          if (first && last)
            computeLir.emitSIMD(
              accumulate = false,
              SIMDOp.Move,
              SIMDSource.Input,
              SIMDSource.Input,
              SIMDDestination.Output,
              outputAccAddress,
              inputAccAddress
            )
          else if (first)
            computeLir.emitSIMD(
              accumulate = false,
              SIMDOp.Max,
              SIMDSource.Input,
              SIMDSource.Input,
              SIMDDestination.Register1,
              MemoryAddress.Invalid,
              inputAccAddress
            )
          else if (last)
            computeLir.emitSIMD(
              accumulate = false,
              SIMDOp.Max,
              SIMDSource.Input,
              SIMDSource.Register1,
              SIMDDestination.Output,
              outputAccAddress,
              inputAccAddress
            )
          else
            computeLir.emitSIMD(
              accumulate = false,
              SIMDOp.Max,
              SIMDSource.Input,
              SIMDSource.Register1,
              SIMDDestination.Register1,
              MemoryAddress.Invalid,
              inputAccAddress
            )
        } else if (poolNode.op == "AvgPool") {
          if (first)
            computeLir.emitSIMD(
              accumulate = false,
              SIMDOp.Max,
              SIMDSource.Input,
              SIMDSource.Input,
              SIMDDestination.Register1,
              MemoryAddress.Invalid,
              inputAccAddress
            )
          else {
            computeLir.emitSIMD(
              accumulate = false,
              SIMDOp.Add,
              SIMDSource.Input,
              SIMDSource.Register1,
              SIMDDestination.Register1,
              MemoryAddress.Invalid,
              inputAccAddress
            )

            if (last) {
              val multiplierAccAddress = locateAccumulator(
                poolNode.multiplier.get
              )

              computeLir.emitSIMD(
                accumulate = false,
                SIMDOp.Multiply,
                SIMDSource.Input,
                SIMDSource.Register1,
                SIMDDestination.Output,
                outputAccAddress,
                multiplierAccAddress
              )
            }
          }
        } else
          throw new CompilerException(
            s"Unsupported pooling operation ${poolNode.op}"
          )

      }
    }

    val saveAccRollup = new DoubleAddressRollup(
      computeLir
        .emitDataMove(toLocal = true, accumulate = false, _, _, _, _, _),
      arch
    )

    for (
      saveNode <-
        nodes
          .filter(_.isInstanceOf[SaveNode])
          .map(_.asInstanceOf[SaveNode])
          .sortBy(_.output)
    ) {
      val inputAccAddress    = locateAccumulator(saveNode.input)
      val outputLocalAddress = allocateLocal(saveNode.output)

      saveAccRollup.emit(
        outputLocalAddress,
        inputAccAddress
      )
    }

    saveAccRollup.finalEmit()

    val saveLocalRollup = new DoubleAddressRollup(
      saveLir
        .emitDataMove(toLocal = false, accumulate = false, _, _, _, _, _),
      arch
    )

    for (
      saveNode <-
        nodes
          .filter(_.isInstanceOf[SaveNode])
          .map(_.asInstanceOf[SaveNode])
          .sortBy(_.output)
    ) {
      val inputLocalAddress = locateLocal(saveNode.output)
      val outputVarsAddress = saveNode.output

      saveLocalRollup.emit(
        inputLocalAddress,
        outputVarsAddress
      )
    }

    saveLocalRollup.finalEmit()
    loadLir.endEmit()
    computeLir.endEmit()
    saveLir.endEmit()
  }
}
