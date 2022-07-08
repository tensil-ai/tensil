/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io.{
  FileOutputStream,
  ObjectOutputStream,
  FileInputStream,
  ObjectInputStream,
  EOFException,
  DataOutputStream
}

import scala.collection.mutable
import scala.util.hashing.MurmurHash3

import tensil.tools.{
  CompilerException,
  CompilerOptions,
  TraceContext,
  TracepointCondition,
}
import tensil.tools.compiler.scheduler._
import tensil.tools.util
import tensil.{TablePrinter, TableLine, Architecture}

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

abstract class Scheduler(
    layerIndex: Int,
    context: SchedulingContext
) extends HIR {
  if (context.options.printProgress) {
    println(s"LAYER $layerIndex, emitting HIR ...")
  }

  private var tempOutputNodesByOutput =
    mutable.Map.empty[MemoryAddress, TempOutputNode]
  private var varOutputNodesByInput =
    mutable.Map.empty[MemoryAddress, mutable.ArrayBuffer[VarOutputNode]]

  protected var macs = 0L

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
      util.divCeil(
        weightsObj.dims.heightVectors,
        context.options.arch.arraySize
      )

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
          for (k <- 0 until context.options.arch.arraySize)
            yield weightsObj.mkAddress(
              i + (k + j * context.options.arch.arraySize) * weightsObj.dims.widthVectors
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
      val matMulNode = tempOutputNodesByOutput
        .getOrElseUpdate(
          output,
          new MatMulNode(Vector.empty[MatMulInput], output)
        )
        .asInstanceOf[MatMulNode]

      tempOutputNodesByOutput(output) =
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
      require(!tempOutputNodesByOutput.contains(output))
      tempOutputNodesByOutput(output) = new LoadNode(
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
      require(!tempOutputNodesByOutput.contains(output))
      tempOutputNodesByOutput(output) = new AddNode(
        input0Obj.mkAddress(i),
        input1Obj.mkAddress(i),
        output
      )
    }
  }

  def emitSub(
      input0Obj: MemoryObject,
      input1Obj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {
    require(input0Obj.dims.sizeVectors == outputObj.dims.sizeVectors)
    require(input1Obj.dims.sizeVectors == outputObj.dims.sizeVectors)
    for (i <- 0 until outputObj.dims.sizeVectors) {
      val output = outputObj.mkAddress(i)
      require(!tempOutputNodesByOutput.contains(output))
      tempOutputNodesByOutput(output) = new BinarySIMDNode(
        SIMDOp.Subtract,
        input0Obj.mkAddress(i),
        input1Obj.mkAddress(i),
        output
      )
    }
  }

  def emitMul(
      input0Obj: MemoryObject,
      input1Obj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {
    require(input0Obj.dims.sizeVectors == outputObj.dims.sizeVectors)
    require(input1Obj.dims.sizeVectors == outputObj.dims.sizeVectors)
    for (i <- 0 until outputObj.dims.sizeVectors) {
      val output = outputObj.mkAddress(i)
      require(!tempOutputNodesByOutput.contains(output))
      tempOutputNodesByOutput(output) = new BinarySIMDNode(
        SIMDOp.Multiply,
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
      require(!tempOutputNodesByOutput.contains(output))
      tempOutputNodesByOutput(output) = new ReluNode(
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
      require(!tempOutputNodesByOutput.contains(output))
      tempOutputNodesByOutput(output) = new SoftmaxNode(
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
      require(!tempOutputNodesByOutput.contains(output))
      tempOutputNodesByOutput(output) = new LeakyReluNode(
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
      require(!tempOutputNodesByOutput.contains(output))
      val node = tempOutputNodesByOutput(output) = new PoolNode(
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
      require(!tempOutputNodesByOutput.contains(output))
      tempOutputNodesByOutput(output) = new NormNode(
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
      require(!tempOutputNodesByOutput.contains(output))
      tempOutputNodesByOutput(output) = new InterpolateNode(
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
      val input = inputObj.mkAddress(i)
      val nodes = varOutputNodesByInput.getOrElseUpdate(
        input,
        mutable.ArrayBuffer.empty[VarOutputNode]
      )
      nodes += new SaveNode(
        input,
        outputObj.mkAddress(i)
      )
    }
  }

  def saveGraph(fileName: String): Unit = {
    val stream = new ObjectOutputStream(new FileOutputStream(fileName))

    for (node <- varOutputNodesByInput.values)
      stream.writeObject(node)

    for (node <- tempOutputNodesByOutput.values)
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
          for (temp <- varOutputNode.inputTemps) {
            val nodes = varOutputNodesByInput.getOrElseUpdate(
              temp,
              mutable.ArrayBuffer.empty[VarOutputNode]
            )
            nodes += varOutputNode
          }
        } else if (node.isInstanceOf[TempOutputNode]) {
          val tempOutputNode = node.asInstanceOf[TempOutputNode]
          tempOutputNodesByOutput(tempOutputNode.output) = tempOutputNode
        }
      }
    } catch {
      case _: EOFException =>
    }

    stream.close()
  }

  def emit(backend: Backend): SchedulerResult = {
    val tempInputTemps =
      tempOutputNodesByOutput.values.map(_.inputTemps).flatten.toSet
    val varInputTemps = varOutputNodesByInput.keys.toSet

    /**
      * Roots are temporary memory addresses that are the input to at
      * least one `VarOutputNode` and are not the the input to any of
      * the `TempOutputNode`s. In other words, roots are computation
      * results not used in any other computation within the current
      * layer.
      */
    val roots = (varInputTemps -- tempInputTemps).toSeq

    if (context.options.printProgress) {
      println(
        s"Emitted ${roots.size} root and ${(varOutputNodesByInput.size + tempOutputNodesByOutput.size) - roots.size} non-root node(s)"
      )
      println(s"Planning ...")
    }

    doEmit(roots, backend)
  }

  protected def doEmit(
      roots: Seq[MemoryAddress],
      backend: Backend
  ): SchedulerResult

  protected def inputsToLoad(
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

  protected def findVarOutputNodesByInput(
      inputTemp: MemoryAddress
  ): Seq[VarOutputNode] =
    varOutputNodesByInput.get(inputTemp) match {
      case None        => Nil
      case Some(nodes) => nodes
    }

  protected def traverseRoots(
      roots: Seq[MemoryAddress]
  ): Seq[Node] = {
    val uniqueTempOutputNodes = mutable.Map.empty[MemoryAddressRaw, Node]
    val uniqueVarOutputNodes  = mutable.Map.empty[MemoryAddressRaw, Seq[Node]]

    def traverseTemp(temp: MemoryAddress) {
      val tempOutputNode = tempOutputNodesByOutput(temp)
      uniqueTempOutputNodes(temp.raw) = tempOutputNode

      for (temp <- tempOutputNode.inputTemps) {
        traverseTemp(temp)
      }

      uniqueVarOutputNodes(temp.raw) = findVarOutputNodesByInput(temp)
    }

    for (temp <- roots)
      traverseTemp(temp)

    uniqueTempOutputNodes.values.toSeq ++ uniqueVarOutputNodes.values.flatten.toSeq
  }

  protected def estimateAccumulatorSize(
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

  protected def estimateLocalSize(
      nodes: Seq[Node],
      includeReusableConsts: Boolean = true,
      includeNonReusableConsts: Boolean = true,
      includeVars: Boolean = true
  ): Int = {
    val unique = mutable.Set.empty[MemoryAddress]

    for (
      node <-
        nodes
          .filter(_.isInstanceOf[TempOutputNode])
          .map(_.asInstanceOf[TempOutputNode])
    ) {
      if (includeReusableConsts)
        unique ++= node.inputReusableConsts

      if (includeNonReusableConsts)
        unique ++= node.inputNonReusableConsts

      if (includeVars)
        unique ++= node.inputVars
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

  protected def emitLoadConsts(
      lir: LIR,
      localAllocator: RenamingMemoryAllocator,
      nodes: Seq[Node],
      includeReusableConsts: Boolean = true,
      includeNonReusableConsts: Boolean = true,
  ): Unit = {
    if (includeReusableConsts)
      emitLoadMemory(
        lir,
        localAllocator,
        inputsToLoad(nodes, _.inputReusableConsts)
      )

    if (includeNonReusableConsts)
      emitLoadMemory(
        lir,
        localAllocator,
        inputsToLoad(nodes, _.inputNonReusableConsts)
      )
  }

  protected def emitLoadVars(
      lir: LIR,
      localAllocator: RenamingMemoryAllocator,
      nodes: Seq[Node]
  ): Unit =
    emitLoadMemory(lir, localAllocator, inputsToLoad(nodes, _.inputVars))

  protected def emitLoadMemory(
      lir: LIR,
      localAllocator: RenamingMemoryAllocator,
      addressesToLoad: Seq[MemoryAddress]
  ): Unit = {
    val loadLocalRollup = new DoubleAddressRollup(
      lir.emitDataMove(toLocal = true, accumulate = false, _, _, _, _, _),
      context.options.arch
    )

    for (
      address <- addressesToLoad.filter(a =>
        a.tag == MemoryTag.DRAM0 || a.tag == MemoryTag.DRAM1
      )
    )
      loadLocalRollup.emit(
        localAllocator.allocate(address),
        address
      )

    loadLocalRollup.finalEmit()
  }

  protected def emitCompute(
      lir: LIR,
      localAllocatorToLoad: RenamingMemoryAllocator,
      localAllocatorToSave: RenamingMemoryAllocator,
      nodes: Seq[Node]
  ): Unit = {
    val accumulatorSpace = ArenaMemorySpace(
      "Accumulator",
      MemoryTag.Accumulators,
      context.options.arch.accumulatorDepth
    )

    val accumulatorAllocator =
      RenamingMemoryAllocator(accumulatorSpace, Set(MemoryTag.Temp))

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
      val outputAccAddress = accumulatorAllocator.allocate(mmNode.output)

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
              localAllocatorToLoad.locate(input.input),
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
        lir.emitLoadWeights(_, _, _),
        context.options.arch
      )

      for (weights <- weightsSeq) {
        val weightsLocalAddress =
          if (weights.tag != MemoryTag.Zeroes)
            localAllocatorToLoad.locate(weights)
          else weights
        loadWeightsRollup.emit(weightsLocalAddress)
      }

      loadWeightsRollup.finalEmit()

      val matMulRollup = new DoubleAddressRollup(
        lir.emitMatMul(accumulate = false, _, _, _, _, _),
        context.options.arch
      )
      val matMulAccumulateRollup = new DoubleAddressRollup(
        lir.emitMatMul(accumulate = true, _, _, _, _, _),
        context.options.arch
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
      lir
        .emitDataMove(toLocal = false, accumulate = false, _, _, _, _, _),
      context.options.arch
    )

    val loadAccInputOutputs = nodes
      .filter(_.isInstanceOf[LoadNode])
      .map(_.asInstanceOf[LoadNode])
      .map(n => (localAllocatorToLoad.locate(n.input), n.output))

    for (
      (inputLocalAddress, outputTempAddress) <- loadAccInputOutputs.sortBy(_._1)
    ) {
      val outputAccAddress =
        accumulatorAllocator.allocate(outputTempAddress, locate = true)

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
      val outputAccAddress = accumulatorAllocator.allocate(addNode.output)
      val inputAccAddress  = accumulatorAllocator.locate(addNode.input0)

      lir.emitSIMD(
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
      lir
        .emitDataMove(toLocal = false, accumulate = true, _, _, _, _, _),
      context.options.arch
    )

    for (
      addNode <-
        nodes
          .filter(_.isInstanceOf[AddNode])
          .map(_.asInstanceOf[AddNode])
          .sortBy(_.output)
    ) {
      val outputAccAddress   = accumulatorAllocator.locate(addNode.output)
      val input1LocalAddress = localAllocatorToLoad.locate(addNode.input1)

      addRollup.emit(
        input1LocalAddress,
        outputAccAddress,
      )
    }

    addRollup.finalEmit()

    for (
      binarySIMDNode <-
        nodes
          .filter(_.isInstanceOf[BinarySIMDNode])
          .map(_.asInstanceOf[BinarySIMDNode])
          .sortBy(_.output)
    ) {
      val outputAccAddress =
        accumulatorAllocator.allocate(binarySIMDNode.output)
      val input0AccAddress =
        accumulatorAllocator.locate(binarySIMDNode.input0)
      val input1AccAddress =
        accumulatorAllocator.locate(binarySIMDNode.input1)

      lir.emitSIMD(
        accumulate = false,
        SIMDOp.Move,
        SIMDSource.Input,
        0,
        SIMDDestination.Register1,
        MemoryAddress.Invalid,
        input0AccAddress
      )

      lir.emitSIMD(
        accumulate = false,
        binarySIMDNode.op,
        SIMDSource.Register1,
        SIMDSource.Input,
        SIMDDestination.Output,
        outputAccAddress,
        input1AccAddress
      )
    }

    for (
      normNode <-
        nodes
          .filter(_.isInstanceOf[NormNode])
          .map(_.asInstanceOf[NormNode])
          .sortBy(_.output)
    ) {
      val outputAccAddress = accumulatorAllocator.allocate(normNode.output)
      val scaleAccAddress  = accumulatorAllocator.locate(normNode.scale)
      val offsetAccAddress = accumulatorAllocator.locate(normNode.offset)
      val inputAccAddress  = accumulatorAllocator.locate(normNode.input)

      lir.emitSIMD(
        accumulate = false,
        SIMDOp.Move,
        SIMDSource.Input,
        0,
        SIMDDestination.Register1,
        MemoryAddress.Invalid,
        inputAccAddress
      )

      lir.emitSIMD(
        accumulate = false,
        SIMDOp.Multiply,
        SIMDSource.Input,
        SIMDSource.Register1,
        SIMDDestination.Register1,
        MemoryAddress.Invalid,
        scaleAccAddress
      )

      lir.emitSIMD(
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
      val outputAccAddress =
        accumulatorAllocator.allocate(interpolateNode.output)

      for (i <- 0 until interpolateNode.scales.size) {
        val scaleAccAddress =
          accumulatorAllocator.locate(interpolateNode.scales(i))
        val inputAccAddress =
          accumulatorAllocator.locate(interpolateNode.inputs(i))

        lir.emitSIMD(
          accumulate = false,
          SIMDOp.Move,
          SIMDSource.Input,
          0,
          SIMDDestination.Register1,
          MemoryAddress.Invalid,
          inputAccAddress
        )

        lir.emitSIMD(
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
      val outputAccAddress = accumulatorAllocator.allocate(reluNode.output)
      val inputAccAddress  = accumulatorAllocator.locate(reluNode.input)

      if (!reluInited) {
        reluInited = true
        lir.emitSIMD(
          accumulate = false,
          SIMDOp.Zero,
          0,
          0,
          SIMDDestination.Register1,
          MemoryAddress.Invalid,
          MemoryAddress.Invalid
        )
      }

      lir.emitSIMD(
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
      val outputAccAddress = accumulatorAllocator.allocate(softmaxNode.output)
      val inputAccAddress  = accumulatorAllocator.locate(softmaxNode.input)

      // TODO: Implement Softmax
      lir.emitSIMD(
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
      val outputAccAddress = accumulatorAllocator.allocate(leakyReluNode.output)
      val alphaAccAddress  = accumulatorAllocator.locate(leakyReluNode.alpha)
      val inputAccAddress  = accumulatorAllocator.locate(leakyReluNode.input)

      lir.emitSIMD(
        accumulate = false,
        SIMDOp.Move,
        SIMDSource.Input,
        0,
        SIMDDestination.Register1,
        MemoryAddress.Invalid,
        alphaAccAddress
      )

      lir.emitSIMD(
        accumulate = false,
        SIMDOp.Multiply,
        SIMDSource.Input,
        SIMDSource.Register1,
        SIMDDestination.Register1,
        MemoryAddress.Invalid,
        inputAccAddress
      )

      lir.emitSIMD(
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
      val outputAccAddress = accumulatorAllocator.allocate(poolNode.output)

      for (i <- 0 until poolNode.inputs.length) {
        val inputAccAddress = accumulatorAllocator.locate(poolNode.inputs(i))

        val first = i == 0
        val last  = i == poolNode.inputs.length - 1

        if (poolNode.op == "MaxPool") {
          if (first && last)
            lir.emitSIMD(
              accumulate = false,
              SIMDOp.Move,
              SIMDSource.Input,
              SIMDSource.Input,
              SIMDDestination.Output,
              outputAccAddress,
              inputAccAddress
            )
          else if (first)
            lir.emitSIMD(
              accumulate = false,
              SIMDOp.Max,
              SIMDSource.Input,
              SIMDSource.Input,
              SIMDDestination.Register1,
              MemoryAddress.Invalid,
              inputAccAddress
            )
          else if (last)
            lir.emitSIMD(
              accumulate = false,
              SIMDOp.Max,
              SIMDSource.Input,
              SIMDSource.Register1,
              SIMDDestination.Output,
              outputAccAddress,
              inputAccAddress
            )
          else
            lir.emitSIMD(
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
            lir.emitSIMD(
              accumulate = false,
              SIMDOp.Max,
              SIMDSource.Input,
              SIMDSource.Input,
              SIMDDestination.Register1,
              MemoryAddress.Invalid,
              inputAccAddress
            )
          else {
            lir.emitSIMD(
              accumulate = false,
              SIMDOp.Add,
              SIMDSource.Input,
              SIMDSource.Register1,
              SIMDDestination.Register1,
              MemoryAddress.Invalid,
              inputAccAddress
            )

            if (last) {
              val multiplierAccAddress = accumulatorAllocator.locate(
                poolNode.multiplier.get
              )

              lir.emitSIMD(
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
      lir
        .emitDataMove(toLocal = true, accumulate = false, _, _, _, _, _),
      context.options.arch
    )

    for (
      saveNode <-
        nodes
          .filter(_.isInstanceOf[SaveNode])
          .map(_.asInstanceOf[SaveNode])
          .sortBy(_.output)
    ) {
      val inputAccAddress = accumulatorAllocator.locate(saveNode.input)
      val outputLocalAddress =
        localAllocatorToSave.allocate(saveNode.output, locate = true)

      saveAccRollup.emit(
        outputLocalAddress,
        inputAccAddress
      )
    }

    saveAccRollup.finalEmit()
  }

  protected def emitSaveMemory(
      lir: LIR,
      addressePairsToSave: Seq[(MemoryAddress, MemoryAddress)]
  ): Unit = {
    val saveLocalRollup = new DoubleAddressRollup(
      lir.emitDataMove(toLocal = false, accumulate = false, _, _, _, _, _),
      context.options.arch
    )

    for (
      (localAddress, dram0Address) <- addressePairsToSave.filter(pair =>
        pair._1.tag == MemoryTag.Local && pair._2.tag == MemoryTag.DRAM0
      )
    )
      saveLocalRollup.emit(
        localAddress,
        dram0Address
      )

    saveLocalRollup.finalEmit()
  }

  protected def emitSaveVars(
      lir: LIR,
      localAllocator: RenamingMemoryAllocator,
      nodes: Seq[Node]
  ): Unit =
    emitSaveMemory(
      lir,
      nodes
        .filter(_.isInstanceOf[SaveNode])
        .map(_.asInstanceOf[SaveNode])
        .filter(_.output.tag == MemoryTag.DRAM0)
        .sortBy(_.output)
        .map(n => (localAllocator.locate(n.output), n.output))
    )
}
