/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io.OutputStream

import scala.collection.mutable

import org.tensorflow.framework.graph.GraphDef
import org.tensorflow.framework.node_def.NodeDef
import org.tensorflow.framework.types.DataType

import _root_.tensil.tools.{
  CompilerException,
  TracepointCondition
}
import _root_.tensil.tools.data.{Shape, TensorData}
import _root_.tensil.tools.util
import _root_.tensil.{TablePrinter, Architecture}

object TfFrontend {
  val ModelFileNameExtension = "pb"
}

class TfFrontend(
    graphDef: GraphDef,
    arch: Architecture,
    inputBatchSize: Int,
    graphStream: Option[OutputStream],
    printSchedulerSummary: Boolean,
    printLayersSummary: Boolean,
    printProgress: Boolean
) extends Frontend {

  private object VarsDimensions {
    def apply(
        number: Int,
        height: Int,
        width: Int,
        channel: Int
    ): MemoryDimensions =
      MemoryDimensions(
        arraySize = arch.arraySize,
        "NHWC",
        "NHWC",
        isWeights = false,
        dimensions = Vector(number, height, width, channel)
      )

    def apply(number: Int, height: Int): MemoryDimensions =
      MemoryDimensions(
        arraySize = arch.arraySize,
        "NH",
        "NH",
        isWeights = false,
        dimensions = Vector(number, height)
      )

    def apply(height: Int): MemoryDimensions =
      MemoryDimensions(
        arraySize = arch.arraySize,
        "H",
        "H",
        isWeights = false,
        dimensions = Vector(height)
      )

    def apply(shape: Shape): MemoryDimensions =
      if (shape.size == 1)
        VarsDimensions(shape(0))
      else if (shape.size == 2)
        VarsDimensions(shape(0), shape(1))
      else if (shape.size == 4)
        VarsDimensions(shape(0), shape(1), shape(2), shape(3))
      else
        throw new CompilerException(
          s"Vars tensor shape of ${shape} is not supported"
        )
  }

  private object ConstsDimensions {
    def apply(width: Int): MemoryDimensions =
      MemoryDimensions(
        arraySize = arch.arraySize,
        "W",
        "W",
        isWeights = true,
        dimensions = Vector(width)
      )

    def apply(height: Int, width: Int): MemoryDimensions =
      MemoryDimensions(
        arraySize = arch.arraySize,
        "HW",
        "HW",
        isWeights = true,
        dimensions = Vector(height, width)
      )

    def apply(
        channelsOut: Int,
        channelsIn: Int,
        height: Int,
        width: Int
    ): MemoryDimensions =
      MemoryDimensions(
        arraySize = arch.arraySize,
        "HWCCo",
        "HWCCo",
        isWeights = true,
        dimensions = Vector(channelsOut, channelsIn, height, width)
      )

    def apply(shape: Shape): MemoryDimensions =
      if (shape.size == 1)
        ConstsDimensions(shape(0))
      else if (shape.size == 2)
        ConstsDimensions(shape(0), shape(1))
      else if (shape.size == 4)
        ConstsDimensions(shape(0), shape(1), shape(2), shape(3))
      else
        throw new CompilerException(
          s"Consts tensor shape of ${shape} is not supported"
        )
  }

  def mkConstsDimensions(shape: Shape): MemoryDimensions =
    ConstsDimensions(shape)

  private val nodeDefs  = mutable.Map.empty[String, NodeDef]
  private val nodeEdges = mutable.Map.empty[String, Seq[String]]

  private val inputOutputs =
    mutable.Map.empty[String, mutable.ArrayBuffer[String]]

  private def inputOutputToNodeName(inputName: String) = inputName.split(":")(0)

  for (nodeDef <- graphDef.node) {
    val nodeName = nodeDef.name

    nodeDefs(nodeName) = nodeDef
    nodeEdges(nodeName) =
      nodeDef.input.filter(!_.startsWith("^")).map(inputOutputToNodeName(_))

    for (inputName <- nodeDef.input) {
      inputOutputs.getOrElseUpdate(
        inputName,
        mutable.ArrayBuffer.empty[String]
      ) += nodeName
    }
  }

  val graphPrinter =
    graphStream.map(new TfFrontendGraphPrinter(_, "model", arch.arraySize))

  /*
   * Traverse function takes the names of the TF output representing
   * the result of the computation. Then, it uses `nodeEdges` to recursively
   * traverse the TF graph. By doing this, it constructs the list of node
   * names that reflect the data flow dependencies.
   */
  def traverse(outputNames: Seq[String]): Seq[String] =
    outputNames
      .map(inputOutputToNodeName(_))
      .distinct
      .foldLeft(Seq.empty[String])(
        recursiveTraverse(_, _)
      )
      .distinct

  private def recursiveTraverse(
      prev: Seq[String],
      nodeName: String
  ): Seq[String] =
    nodeEdges(nodeName)
      .foldLeft(nodeName +: prev)(
        recursiveTraverse(_, _)
      )
      .distinct

  /*
   * Rewrite function takes the list of node names previously
   * constructed by the traverse function and maps it to TF
   * definition list. Then function inspects a TF node definition
   * from the definition list in the order that reflects the
   * data flow dependencies. This inspection results in:
   *
   *   a) skipping the definition,
   *   b) taking additional TF node definition from the definition
   *      list to inspect in combination with the current node
   *      definition (see `rewriteConst`), or
   *   c) adding a function closure to the emit list.
   *
   * The emit list contains functions that take an emit context and
   * captures all necessary backend and memory managment operations to
   * emit the instructions representing the rewritten TF node(s).
   * The recursion continues until the definition list is empty.
   */
  def rewrite(program: Seq[String]): Seq[Emitter] = {
    recursiveRewrite(
      program.map(nodeName => nodeDefs(nodeName))
    ).reverse :+ (emitOutput(_))
  }

  private def recursiveRewrite(
      defs: Seq[NodeDef],
      emitters: Seq[Emitter] = Nil
  ): Seq[Emitter] = {
    defs match {
      case Nil => emitters
      case nodeDef :: remainingDefs =>
        nodeDef.op match {
          case "MatMul" | "Conv2D" =>
            rewriteLayer(remainingDefs, nodeDef, emitters)
          case "Const" =>
            rewriteSimple(remainingDefs, emitConst(_, nodeDef), emitters)
          case "Placeholder" =>
            rewriteSimple(remainingDefs, emitPlaceholder(_, nodeDef), emitters)
          case "Reshape" =>
            rewriteSimple(remainingDefs, emitReshape(_, nodeDef), emitters)
          case "Shape" =>
            rewriteSimple(remainingDefs, emitShape(_, nodeDef), emitters)
          case "StridedSlice" =>
            rewriteSimple(remainingDefs, emitStridedSlice(_, nodeDef), emitters)
          case "Pack" =>
            rewriteSimple(remainingDefs, emitPack(_, nodeDef), emitters)
          case "Tile" =>
            rewriteSimple(remainingDefs, emitTile(_, nodeDef), emitters)
          case "Cast" =>
            rewriteSimple(remainingDefs, emitCast(_, nodeDef), emitters)
          case "Pad" =>
            rewriteSimple(remainingDefs, emitPad(_, nodeDef), emitters)
          case "Split" | "SplitV" =>
            rewriteSimple(remainingDefs, emitSplit(_, nodeDef), emitters)
          case "ConcatV2" =>
            rewriteSimple(remainingDefs, emitConcat(_, nodeDef), emitters)
          case "ResizeBilinear" =>
            rewriteSimple(
              remainingDefs,
              emitResizeBilinear(_, nodeDef),
              emitters
            )
          case "MaxPool" | "AvgPool" =>
            rewriteSimple(remainingDefs, emitPool(_, nodeDef), emitters)
          case "FusedBatchNormV3" =>
            rewriteSimple(remainingDefs, emitNorm(_, nodeDef), emitters)
          case "Relu" | "Softmax" | "LeakyRelu" =>
            rewriteSimple(remainingDefs, emitActivate(_, nodeDef), emitters)
          case "AddV2" =>
            rewriteSimple(remainingDefs, emitAdd(_, nodeDef), emitters)
          case "Mean" =>
            rewriteSimple(remainingDefs, emitMean(_, nodeDef), emitters)
          case "Identity" =>
            rewriteSimple(remainingDefs, emitIdentity(_, nodeDef), emitters)
          case op =>
            throw new CompilerException(
              s"Unsupported op ${op} (${nodeDef.name})"
            )
        }
    }
  }

  private def rewriteLayer(
      defs: Seq[NodeDef],
      startNodeDef: NodeDef,
      emitters: Seq[Emitter]
  ) =
    recursiveRewriteLayer(
      Seq(
        Set("BiasAdd"),
        Set("AddV2"),
        Set("FusedBatchNormV3"),
        Set("Relu", "Softmax", "LeakyRelu"),
        Set("MaxPool", "AvgPool")
      ),
      defs,
      Seq(Some(startNodeDef)),
      emitters
    )

  /*
   * This function takes `layerStepOps`, which describes
   * the pattern to which we expect TF nodes to adhere in order
   * to form a layer. The initial and the only required node is
   * matched in `recursiveRewrite` to be either `MatMul` or
   * `Conv2D`. This node is followed by "layer step operations"
   * where each step can optionally be one of the operations
   * included in the set.
   *
   * The function recurses by taking one step at the time and
   * taking corresponding TF nodes until `layerStepOps` is empty
   * and `layerDefs` is filled with matching TF nodes.
   */
  private def recursiveRewriteLayer(
      layerStepOps: Seq[Set[String]],
      defs: Seq[NodeDef],
      layerDefs: Seq[Option[NodeDef]],
      emitters: Seq[Emitter]
  ): Seq[Emitter] = {
    def doIt() = {
      val (poolDef :: activateDef :: normDef :: addDef :: biasDef :: nodeDef :: Nil) =
        Seq.fill[Option[NodeDef]](6 - layerDefs.size)(None) ++ layerDefs

      val emitter = doRewriteLayer(
        nodeDef.get,
        biasDef,
        addDef,
        normDef,
        activateDef,
        poolDef
      )

      recursiveRewrite(defs, emitter +: emitters)
    }

    layerStepOps match {
      case Nil => doIt()

      case stepOps :: layerStepOps =>
        defs match {
          case Nil => doIt()

          case nodeDef :: defs =>
            val prevDef = layerDefs.find(_.isDefined).get.get

            if (
              stepOps.contains(nodeDef.op) &&
              nodeDef.input.contains(prevDef.name)
            )
              recursiveRewriteLayer(
                layerStepOps,
                defs,
                Some(nodeDef) +: layerDefs,
                emitters
              )
            else
              recursiveRewriteLayer(
                layerStepOps,
                nodeDef +: defs,
                None +: layerDefs,
                emitters
              )
        }
    }
  }

  private def rewriteSimple(
      defs: Seq[NodeDef],
      emitter: Emitter,
      emitters: Seq[Emitter]
  ): Seq[Emitter] =
    recursiveRewrite(defs, emitter +: emitters)

  private var layerIndex = 0

  private def startLayer(nodeDefs: Seq[NodeDef]): Scheduler = {
    val name = s"LAYER $layerIndex"

    if (printLayersSummary) {
      val tb = new TablePrinter(Some(s"$name SUMMARY"))
      for (nodeDef <- nodeDefs)
        tb.addNamedLine(nodeDef.op, nodeDef.name)
      print(tb)
    }

    layerIndex += 1

    new Scheduler(
      name,
      arch,
      printSchedulerSummary,
      printProgress
    )
  }

  /*private def finishLayer(scheduler: Scheduler, context: EmitContext) = {
    scheduler.saveGraph(s"tf_layer_${layerIndex - 1}.tgraph")
    None
  }*/

  private def finishLayer(scheduler: Scheduler, context: EmitContext) =
    Some(
      scheduler.emit(
        context.backend,
        context.backendStats
      )
    )

  private def doRewriteLayer(
      nodeDef: NodeDef,
      biasDef: Option[NodeDef],
      addDef: Option[NodeDef],
      normDef: Option[NodeDef],
      activateDef: Option[NodeDef],
      poolDef: Option[NodeDef]
  ): Emitter =
    (context: EmitContext) => {
      if (graphPrinter.isDefined)
        graphPrinter.get.startLayer(s"layer_$layerIndex")

      val scheduler = startLayer(
        Seq(Some(nodeDef), biasDef, addDef, normDef, activateDef, poolDef)
          .filter(_.isDefined)
          .map(_.get)
      )

      val (consumers, nodeName) =
        if (biasDef.isDefined)
          (
            findInterLayerOutputs(
              context,
              biasDef.get.name,
              addDef.orElse(normDef.orElse(activateDef.orElse(poolDef)))
            ),
            biasDef.get.name
          )
        else
          (
            findInterLayerOutputs(
              context,
              nodeDef.name,
              biasDef.orElse(
                addDef.orElse(normDef.orElse(activateDef.orElse(poolDef)))
              )
            ),
            nodeDef.name
          )

      val matMulTemp =
        if (nodeDef.op == "MatMul")
          emitLayerMatMul(
            context,
            scheduler,
            nodeDef,
            biasDef
          )
        else
          emitLayerConv2D(
            context,
            scheduler,
            nodeDef,
            biasDef
          )

      def emitSaveIfConsumed(
          outputTemp: MemoryObject,
          consumers: Seq[String]
      ): Unit =
        if (!consumers.isEmpty) {
          val outputVars = context.mm.allocateVarsObject(
            outputTemp.name,
            outputTemp.dims,
            consumers
          )

          scheduler.emitSave(outputTemp, outputVars)
        }

      emitSaveIfConsumed(matMulTemp, consumers)

      val addTemp = if (addDef.isDefined) {
        val outputTemp = emitLayerAdd(
          context,
          scheduler,
          addDef.get,
          matMulTemp
        )

        emitSaveIfConsumed(
          outputTemp,
          findInterLayerOutputs(
            context,
            addDef.get.name,
            normDef.orElse(activateDef.orElse(poolDef))
          )
        )

        outputTemp

      } else matMulTemp

      val normTemp = if (normDef.isDefined) {
        val outputTemp = emitLayerNorm(
          context,
          scheduler,
          normDef.get,
          addTemp
        )

        emitSaveIfConsumed(
          outputTemp,
          findInterLayerOutputs(
            context,
            normDef.get.name,
            activateDef.orElse(poolDef)
          )
        )

        outputTemp

      } else addTemp

      val activateTemp =
        if (activateDef.isDefined) {
          val outputTemp = emitLayerActivate(
            context,
            scheduler,
            activateDef.get,
            normTemp
          )

          emitSaveIfConsumed(
            outputTemp,
            findInterLayerOutputs(context, activateDef.get.name, poolDef)
          )

          outputTemp

        } else normTemp

      if (poolDef.isDefined) {
        val outputTemp =
          emitLayerPool(context, scheduler, poolDef.get, activateTemp)

        emitSaveIfConsumed(
          outputTemp,
          findInterLayerOutputs(context, poolDef.get.name, None)
        )

      }

      if (graphPrinter.isDefined)
        graphPrinter.get.endLayer()

      finishLayer(scheduler, context)
    }

  private def findInterLayerOutputs(
      context: EmitContext,
      name: String,
      nextNode: Option[NodeDef]
  ): Seq[String] =
    if (context.outputNames.contains(name))
      Seq("pin") // TODO: we need another mechanism to pin output objects
    else
      inputOutputs.get(name) match {
        case Some(outputs) =>
          (if (nextNode.isDefined) outputs - nextNode.get.name
           else outputs).toSeq
        case None => Nil
      }

  private def emitIdentity(
      context: EmitContext,
      identifyDef: NodeDef
  ): EmitResult = {
    if (context.mm.hasObject(identifyDef.input(0))) {
      val inputVars =
        context.mm
          .consumeObject(identifyDef.input(0), Seq(identifyDef.name))

      val outputVars = context.mm.blendObjects(
        identifyDef.name,
        inputVars.dims,
        findInterLayerOutputs(context, identifyDef.name, None),
        Seq(inputVars.name),
        inputVars.span
      )

      if (graphPrinter.isDefined)
        graphPrinter.get.printOp(
          identifyDef,
          Seq(outputVars),
          Seq(inputVars)
        )
    } else {
      context.mm.aliasPendingConst(identifyDef.input(0), identifyDef.name)
    }

    None
  }

  private def emitPlaceholder(
      context: EmitContext,
      placeholderDef: NodeDef
  ): EmitResult = {
    val shape = util.getShape(placeholderDef)

    val placeholderShape =
      if (shape(0) == -1)
        Shape(
          inputBatchSize +: shape
            .takeRight(shape.size - 1)
            .toArray
        )
      else shape

    val placeholderDims = VarsDimensions(placeholderShape)

    val placeholderVars = context.mm.emitInputObject(
      placeholderDef.name,
      placeholderDims,
      findInterLayerOutputs(context, placeholderDef.name, None)
    )

    if (graphPrinter.isDefined) {
      graphPrinter.get.printOp(
        placeholderDef,
        Seq(placeholderVars)
      )
      graphPrinter.get.printInputPost(placeholderVars)
    }

    None
  }

  private def emitOutput(
      context: EmitContext
  ): EmitResult = {
    for (outputName <- context.outputNames) {
      val obj = context.mm.emitOutputObject(outputName)

      if (graphPrinter.isDefined)
        graphPrinter.get.printOutputPost(obj)
    }

    if (graphPrinter.isDefined) graphPrinter.get.endPrint

    None
  }

  private def emitConst(
      context: EmitContext,
      constDef: NodeDef
  ): EmitResult = {
    context.mm.addPendingConst(
      constDef.name,
      util.getTensorData(constDef)
    )

    None
  }

  private def emitShape(context: EmitContext, shapeDef: NodeDef): EmitResult = {
    val inputVars =
      context.mm
        .consumeObject(shapeDef.input(0), Seq(shapeDef.name))

    context.mm.addPendingConst(
      shapeDef.name,
      new TensorData(
        Shape(inputVars.dims.order),
        inputVars.dims.modelDimensions.map(_.toInt),
        DataType.DT_INT32
      )
    )

    None
  }

  private def emitStridedSlice(
      context: EmitContext,
      stridedSliceDef: NodeDef
  ): EmitResult = {
    val dtype = stridedSliceDef.attr
      .get("T")
      .get
      .value
      .`type`
      .get

    val input = context.mm.getPendingConst(dtype, stridedSliceDef.input(0))

    val begin = context.mm
      .getPendingIntConst(stridedSliceDef.input(1))
      .as1D
      .toArray
    val end = context.mm
      .getPendingIntConst(stridedSliceDef.input(2))
      .as1D
      .toArray
    val strides = context.mm
      .getPendingIntConst(stridedSliceDef.input(3))
      .as1D
      .toArray

    if (input.shape.size != begin.size)
      throw new CompilerException("Invalid begin in strided slice")

    if (input.shape.size != end.size)
      throw new CompilerException("Invalid end in strided slice")

    if (input.shape.size != strides.size)
      throw new CompilerException("Invalid strides in strided slice")

    if (input.shape.size != 1)
      throw new CompilerException("Only 1D strided slice is supported")

    val beginMask = stridedSliceDef.attr
      .get("begin_mask")
      .get
      .value
      .i
      .get
      .toInt

    if (beginMask != 0)
      throw new CompilerException(
        "Strided slice with begin mask is not supported"
      )

    val ellipsisMask = stridedSliceDef.attr
      .get("ellipsis_mask")
      .get
      .value
      .i
      .get
      .toInt

    if (ellipsisMask != 0)
      throw new CompilerException(
        "Strided slice with ellipsis mask is not supported"
      )

    val endMask = stridedSliceDef.attr
      .get("end_mask")
      .get
      .value
      .i
      .get
      .toInt

    if (endMask != 0)
      throw new CompilerException(
        "Strided slice with end mask is not supported"
      )

    val newAxisMask = stridedSliceDef.attr
      .get("new_axis_mask")
      .get
      .value
      .i
      .get
      .toInt

    if (newAxisMask != 0)
      throw new CompilerException(
        "Strided slice with new axis mask is not supported"
      )

    val shrinkAxisMask = stridedSliceDef.attr
      .get("shrink_axis_mask")
      .get
      .value
      .i
      .get
      .toInt

    if (shrinkAxisMask != 1)
      throw new CompilerException(
        "Only strided slice with shrink axis is supported"
      )

    context.mm.addPendingConst(
      stridedSliceDef.name,
      new TensorData(Shape(), Seq(input.as1D(begin(0))), dtype)
    )

    None
  }

  private def emitPack(
      context: EmitContext,
      packDef: NodeDef
  ): EmitResult = {
    val dtype = packDef.attr
      .get("T")
      .get
      .value
      .`type`
      .get

    val n = packDef.attr
      .get("N")
      .get
      .value
      .i
      .get
      .toInt

    val axis = packDef.attr
      .get("axis")
      .get
      .value
      .i
      .get
      .toInt

    if (axis != 0)
      throw new CompilerException("Only first axis pack is supported")

    val inputs =
      for (i <- 0 until n)
        yield context.mm.getPendingConst(dtype, packDef.input(i))

    for (input <- inputs)
      if (inputs(0).shape != input.shape)
        throw new CompilerException(
          "Pack input shapes and data types must match"
        )

    val packedShape = Shape((Seq(n) ++ inputs(0).shape).toArray)
    val packedData  = inputs.map(_.data).flatten

    context.mm.addPendingConst(
      packDef.name,
      new TensorData(packedShape, packedData, dtype)
    )

    None
  }

  private def emitTile(
      context: EmitContext,
      tileDef: NodeDef
  ): EmitResult = {
    val dtype = tileDef.attr
      .get("T")
      .get
      .value
      .`type`
      .get

    val input = context.mm.getPendingConst(dtype, tileDef.input(0))
    val multiples = context.mm
      .getPendingIntConst(tileDef.input(1))
      .as1D
      .toArray

    val inputShape = input.shape

    if (multiples.size != inputShape.size)
      throw new CompilerException(
        "Tile input shape must match multiples size"
      )

    def tile(base: Int, i: Int): Seq[Any] =
      if (i == inputShape.size)
        Seq(input.data(base))
      else {
        val nextBase = base * inputShape(i)
        val r =
          for (j <- 0 until inputShape(i); k <- 0 until multiples(i))
            yield tile(nextBase + j, i + 1)

        r.flatten.toSeq
      }

    val tiledShape = Shape(
      multiples.zip(inputShape).map({ case (m, s) => m * s })
    )
    val tiledData = tile(0, 0)

    context.mm.addPendingConst(
      tileDef.name,
      new TensorData(tiledShape, tiledData, dtype)
    )

    None
  }

  private def emitCast(
      context: EmitContext,
      castDef: NodeDef
  ): EmitResult = {
    val sourceType = castDef.attr
      .get("SrcT")
      .get
      .value
      .`type`
      .get

    val destinationType = castDef.attr
      .get("DstT")
      .get
      .value
      .`type`
      .get

    if (sourceType != DataType.DT_INT32 || destinationType != DataType.DT_FLOAT)
      throw new CompilerException(
        "Only DT_INT32 to DT_FLOAT cast is supported"
      )

    val input =
      context.mm.getPendingIntConst(castDef.input(0))
    val castedData = input.data.map(_.toFloat)

    context.mm.addPendingConst(
      castDef.name,
      new TensorData(input.shape, castedData, DataType.DT_FLOAT)
    )

    None
  }

  private def emitReshape(
      context: EmitContext,
      reshapeDef: NodeDef
  ): EmitResult = {
    val inputVars =
      context.mm
        .consumeObject(reshapeDef.input(0), Seq(reshapeDef.name))
    val inputDims = inputVars.dims

    val shape = context.mm
      .getPendingIntConst(reshapeDef.input(1))
      .as1D
      .toArray

    var pixelDims = VarsDimensions(1, arch.arraySize)

    val outputDims = VarsDimensions(Shape(if (shape.exists(_ == -1)) {
      val inferred = inputDims.sizeScalars / shape.filter(_ != -1).product
      shape.map(d => if (d == -1) inferred else d)
    } else shape))

    if (inputDims.sizeScalars != outputDims.sizeScalars)
      throw new CompilerException("Scalar sizes must match for reshape")

    val indexesAndOffsetsPairs =
      for (i <- 0 until inputDims.sizeScalars) yield {
        val (inputIndex, inputOffset)   = inputDims.vectorIndexOffsetAt(i)
        val (outputIndex, outputOffset) = outputDims.vectorIndexOffsetAt(i)
        ((outputIndex, inputIndex), (outputOffset, inputOffset))
      }

    val groupedByOffsetPairs = indexesAndOffsetsPairs
      .groupBy(_._1)
      .mapValues(_.map(_._2))
      .groupBy(_._2.sorted)
      .mapValues(_.keys.toIndexedSeq)

    val outputAddresses =
      Array.fill(outputDims.sizeVectors)(MemoryAddress.Invalid)

    val identityOffsetPairs = (0 until arch.arraySize).map(i => (i, i))
    val identityIndexPairs  = groupedByOffsetPairs.get(identityOffsetPairs)

    if (identityIndexPairs.isDefined)
      for (indexPair <- identityIndexPairs.get) {
        outputAddresses(indexPair._1) = inputVars.span(indexPair._2)
      }

    val (outputNames, r) =
      if (groupedByOffsetPairs.size > 1) {
        val scheduler = startLayer(Seq(reshapeDef))
        val adjustedOutputTemp = context.mm.allocateTempObject(
          reshapeDef.name,
          outputDims
        )

        for (
          (offsetPairs, indexPairs) <- groupedByOffsetPairs
          if offsetPairs != identityOffsetPairs
        ) yield {

          for (indexPair <- indexPairs) {
            val pixelWeightsConst =
              getOrEmitAdjustmentWeights(context, offsetPairs)

            val pixelInputVars =
              mkSub(inputVars, indexPair._2, pixelDims)

            val pixelAdjustedOutputTemp =
              mkSub(adjustedOutputTemp, indexPair._1, pixelDims)

            scheduler.emitMatMul(
              pixelWeightsConst,
              None,
              Seq(
                MemoryOptionalInputOutputObjects(
                  Some(pixelInputVars),
                  pixelAdjustedOutputTemp
                )
              )
            )

          }
        }

        val adjustedOutputVars = context.mm.allocateVarsObject(
          s"${adjustedOutputTemp.name}/Adjusted",
          adjustedOutputTemp.dims,
          Nil
        )

        for (
          (offsetPairs, indexPairs) <- groupedByOffsetPairs
          if offsetPairs != identityOffsetPairs;
          indexPair <- indexPairs
        ) {

          scheduler.emitSave(
            mkSub(adjustedOutputTemp, indexPair._1, pixelDims),
            mkSub(adjustedOutputVars, indexPair._1, pixelDims)
          )
          outputAddresses(indexPair._1) = adjustedOutputVars.span(indexPair._1)
        }

        (
          Seq(inputVars.name, adjustedOutputVars.name),
          finishLayer(scheduler, context)
        )
      } else
        (Seq(inputVars.name), None)

    val outputVars = context.mm.blendObjects(
      reshapeDef.name,
      outputDims,
      findInterLayerOutputs(context, reshapeDef.name, None),
      outputNames,
      outputAddresses
    )

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        reshapeDef,
        Seq(outputVars),
        Seq(inputVars)
      )

    r
  }

  private def emitPad(context: EmitContext, padDef: NodeDef): EmitResult = {
    val paddings =
      context.mm
        .getPendingIntConst(padDef.input(1))

    val inputVars =
      context.mm.consumeObject(padDef.input(0), Seq(padDef.name))

    if (paddings.data.size != 8 || inputVars.dims.order != 4)
      throw new CompilerException("Only 4D padding is supported")

    if (
      paddings.data(0) != 0 || paddings.data(1) != 0 || paddings
        .data(6) != 0 || paddings.data(7) != 0
    )
      throw new CompilerException("Only height/width padding is supported")

    val paddingTop    = paddings.data(2)
    val paddingBottom = paddings.data(3)
    val paddingLeft   = paddings.data(4)
    val paddingRight  = paddings.data(5)

    val paddedDims = VarsDimensions(
      inputVars.dims.number,
      paddingTop + inputVars.dims.height + paddingBottom,
      paddingLeft + inputVars.dims.width + paddingRight,
      inputVars.dims.channels
    )

    val paddingSize =
      (inputVars.dims.heightVectors * (paddingLeft + paddingRight) +
        paddedDims.widthVectors * (paddingTop + paddingBottom)) * inputVars.dims.numberVectors * inputVars.dims.channelsVectors

    val paddingName = s"${padDef.name}+Padding"

    context.mm.addPendingConst(
      paddingName,
      TensorData.fill(Shape(paddingSize * arch.arraySize))(0f)
    )

    val paddingVars     = context.mm.getOrEmitConstObject(paddingName)
    val paddedAddresses = mutable.ArrayBuffer.empty[MemoryAddress]
    var l               = 0

    for (
      n <- 0 until inputVars.dims.numberVectors;
      i <- 0 until paddedDims.heightVectors;
      j <- 0 until paddedDims.widthVectors
    ) {

      val (obj, offset) =
        if (
          i >= paddingTop && i < (paddedDims.heightVectors - paddingBottom) && j >= paddingLeft && j < (paddedDims.widthVectors - paddingRight)
        ) {
          val offset =
            ((n * inputVars.dims.heightVectors + (i - paddingTop)) * inputVars.dims.widthVectors + (j - paddingLeft)) * inputVars.dims.channelsVectors
          (inputVars, offset)

        } else {
          val offset = l

          l += inputVars.dims.channelsVectors

          (paddingVars, offset)
        }

      for (k <- 0 until inputVars.dims.channelsVectors) {
        val address = obj.span((offset + k))

        paddedAddresses += address
      }
    }

    val outputVars = context.mm.blendObjects(
      padDef.name,
      paddedDims,
      findInterLayerOutputs(context, padDef.name, None),
      Seq(inputVars.name, paddingVars.name),
      paddedAddresses.toArray
    )

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        padDef,
        Seq(outputVars),
        Seq(inputVars)
      )

    None
  }

  private def emitSplit(context: EmitContext, splitDef: NodeDef): EmitResult = {
    val num = splitDef.attr
      .get("num_split")
      .get
      .value
      .i
      .get
      .toInt

    val (inputVars, sizes, dims) = splitDef.op match {
      case "Split" =>
        val inputVars =
          context.mm.consumeObject(splitDef.input(1), Seq(splitDef.name))
        (
          inputVars,
          Array.fill(num)(inputVars.dims.lastInLayout / num),
          context.mm
            .getPendingIntConst(splitDef.input(0))
            .data
            .toArray
        )
      case "SplitV" =>
        (
          context.mm.consumeObject(splitDef.input(0), Seq(splitDef.name)),
          context.mm
            .getPendingIntConst(splitDef.input(1))
            .data
            .toArray,
          context.mm
            .getPendingIntConst(splitDef.input(2))
            .data
            .toArray
        )
    }

    if (dims.size != 1 || dims(0) != -1)
      throw new CompilerException("Only channel split is supported")

    val inputDims = inputVars.dims

    val outputRanges =
      for (i <- 0 until sizes.size)
        yield (i, sizes.take(i).sum, sizes.take(i + 1).sum)

    var pixelDims = VarsDimensions(1, arch.arraySize)
    val outputsDims = sizes
      .map(size =>
        VarsDimensions(
          inputDims.number,
          inputDims.height,
          inputDims.width,
          size
        )
      )
      .toArray

    val outputsAddresses =
      outputsDims.map(dims =>
        Array.fill(dims.sizeVectors)(MemoryAddress.Invalid)
      )

    val indexesAndOffsetsPairs =
      for (i <- 0 until inputDims.sizeScalars) yield {
        val (inputLastIndex, inputLastOffset, inputIndex, inputOffset) =
          inputDims.lastAndVectorIndexOffsetAt(i)

        val (output, base, _) = outputRanges
          .find({ case (_, _, until) => inputLastOffset < until })
          .get

        val outputDims = outputsDims(output)

        val j =
          inputLastIndex * outputDims.lastInLayout + (inputLastOffset - base)

        val (outputIndex, outputOffset) = outputDims.vectorIndexOffsetAt(j)
        (((output, outputIndex), inputIndex), (outputOffset, inputOffset))
      }

    val groupedByOffsetPairs = indexesAndOffsetsPairs
      .groupBy(_._1)
      .mapValues(_.map(_._2))
      .groupBy(_._2.sorted)
      .mapValues(_.keys.toIndexedSeq)

    val identityOffsetPairs = (0 until arch.arraySize).map(i => (i, i))
    val identityIndexPairs  = groupedByOffsetPairs.get(identityOffsetPairs)

    if (identityIndexPairs.isDefined)
      for (indexPair <- identityIndexPairs.get) {
        outputsAddresses(indexPair._1._1)(indexPair._1._2) =
          inputVars.span(indexPair._2)
      }

    val (outputsNames, r) =
      if (groupedByOffsetPairs.size > 1) {
        val scheduler = startLayer(Seq(splitDef))

        val adjustedOutputsTemp =
          for (i <- 0 until outputsDims.size)
            yield context.mm.allocateTempObject(
              splitName(splitDef.name, i),
              outputsDims(i)
            )

        for (
          (offsetPairs, indexPairs) <- groupedByOffsetPairs
          if offsetPairs != identityOffsetPairs
        ) yield {

          for (indexPair <- indexPairs) {
            val pixelWeightsConst =
              getOrEmitAdjustmentWeights(context, offsetPairs)

            val pixelInputVars =
              mkSub(inputVars, indexPair._2, pixelDims)

            val pixelAdjustedOutputTemp =
              mkSub(
                adjustedOutputsTemp(indexPair._1._1),
                indexPair._1._2,
                pixelDims
              )

            scheduler.emitMatMul(
              pixelWeightsConst,
              None,
              Seq(
                MemoryOptionalInputOutputObjects(
                  Some(pixelInputVars),
                  pixelAdjustedOutputTemp
                )
              )
            )

          }
        }

        val adjustedOutputsVars = adjustedOutputsTemp.map(temp =>
          context.mm.allocateVarsObject(
            s"${temp.name}/Adjusted",
            temp.dims,
            Nil
          )
        )

        for (
          (offsetPairs, indexPairs) <- groupedByOffsetPairs
          if offsetPairs != identityOffsetPairs;
          indexPair <- indexPairs
        ) {

          scheduler.emitSave(
            mkSub(
              adjustedOutputsTemp(indexPair._1._1),
              indexPair._1._2,
              pixelDims
            ),
            mkSub(
              adjustedOutputsVars(indexPair._1._1),
              indexPair._1._2,
              pixelDims
            )
          )
          outputsAddresses(indexPair._1._1)(indexPair._1._2) =
            adjustedOutputsVars((indexPair._1._1))
              .span(indexPair._1._2)
        }

        (
          adjustedOutputsVars
            .map(vars => Seq(inputVars.name, vars.name))
            .toArray,
          finishLayer(scheduler, context)
        )
      } else
        (Array.fill(num)(Seq(inputVars.name)), None)

    val outputsVars = for (i <- 0 until outputsDims.size) yield {
      val name = splitName(splitDef.name, i)
      context.mm.blendObjects(
        name,
        outputsDims(i),
        findInterLayerOutputs(context, name, None),
        outputsNames(i),
        outputsAddresses(i)
      )
    }

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        splitDef,
        outputsVars.toSeq,
        Seq(inputVars)
      )

    r
  }

  private def emitConcat(
      context: EmitContext,
      concatDef: NodeDef
  ): EmitResult = {
    val num = concatDef.attr
      .get("N")
      .get
      .value
      .i
      .get
      .toInt

    val dims =
      context.mm
        .getPendingIntConst(concatDef.input(num))

    if (dims.data.size != 1 || dims.data(0) != -1)
      throw new CompilerException("Only channel concat is supported")

    val inputsVars =
      for (i <- 0 until num)
        yield context.mm.consumeObject(concatDef.input(i), Seq(concatDef.name))

    val inputsDims = inputsVars.map(_.dims)

    for (i <- 1 until inputsDims.size)
      if (
        inputsDims(0).number != inputsDims(i).number ||
        inputsDims(0).height != inputsDims(i).height ||
        inputsDims(0).width != inputsDims(i).width
      )
        throw new CompilerException(
          "All but channel dimensions must match for concat"
        )

    val sizes = inputsDims.map(_.lastInLayout)

    val inputRanges =
      for (i <- 0 until sizes.size)
        yield (i, sizes.take(i).sum, sizes.take(i + 1).sum)

    var pixelDims = VarsDimensions(1, arch.arraySize)
    val outputDims = VarsDimensions(
      inputsVars(0).dims.number,
      inputsVars(0).dims.height,
      inputsVars(0).dims.width,
      sizes.sum
    )

    val outputAddresses =
      Array.fill[MemoryAddress](outputDims.sizeVectors)(MemoryAddress.Invalid)

    val indexesAndOffsetsPairs =
      for (i <- 0 until outputDims.sizeScalars) yield {
        val (outputLastIndex, outputLastOffset, outputIndex, outputOffset) =
          outputDims.lastAndVectorIndexOffsetAt(i)

        val (input, base, _) = inputRanges
          .find({ case (_, _, until) => outputLastOffset < until })
          .get

        val inputDims = inputsDims(input)

        val j =
          outputLastIndex * inputDims.lastInLayout + (outputLastOffset - base)

        val (inputIndex, inputOffset) = inputDims.vectorIndexOffsetAt(j)
        ((outputIndex, (input, inputIndex)), (outputOffset, inputOffset))
      }

    val groupedByOffsetPairs = indexesAndOffsetsPairs
      .groupBy(_._1)
      .mapValues(_.map(_._2))
      .groupBy(_._2.sorted)
      .mapValues(_.keys.toIndexedSeq)

    val identityOffsetPairs = (0 until arch.arraySize).map(i => (i, i))
    val identityIndexPairs  = groupedByOffsetPairs.get(identityOffsetPairs)

    if (identityIndexPairs.isDefined)
      for (indexPair <- identityIndexPairs.get) {
        outputAddresses(indexPair._1) =
          inputsVars(indexPair._2._1).span(indexPair._2._2)
      }

    val (inputNamesToAdd, r) =
      if (groupedByOffsetPairs.size > 1) {
        val scheduler = startLayer(Seq(concatDef))

        val adjustedOutputTemp = context.mm.allocateTempObject(
          concatDef.name,
          outputDims
        )

        for (
          (offsetPairs, indexPairs) <- groupedByOffsetPairs
          if offsetPairs != identityOffsetPairs
        ) yield {

          for (indexPair <- indexPairs) {
            val pixelWeightsConst =
              getOrEmitAdjustmentWeights(context, offsetPairs)

            val pixelInputVars =
              mkSub(inputsVars(indexPair._2._1), indexPair._2._2, pixelDims)

            val pixelAdjustedOutputTemp =
              mkSub(
                adjustedOutputTemp,
                indexPair._1,
                pixelDims
              )

            scheduler.emitMatMul(
              pixelWeightsConst,
              None,
              Seq(
                MemoryOptionalInputOutputObjects(
                  Some(pixelInputVars),
                  pixelAdjustedOutputTemp
                )
              )
            )

          }
        }

        val adjustedOutputVars =
          context.mm.allocateVarsObject(
            s"${adjustedOutputTemp.name}/Adjusted",
            adjustedOutputTemp.dims,
            Nil
          )

        for (
          (offsetPairs, indexPairs) <- groupedByOffsetPairs
          if offsetPairs != identityOffsetPairs;
          indexPair <- indexPairs
        ) {

          scheduler.emitSave(
            mkSub(
              adjustedOutputTemp,
              indexPair._1,
              pixelDims
            ),
            mkSub(
              adjustedOutputVars,
              indexPair._1,
              pixelDims
            )
          )
          outputAddresses(indexPair._1) = adjustedOutputVars.span(indexPair._1)
        }

        (
          Seq(adjustedOutputVars.name),
          finishLayer(scheduler, context)
        )
      } else
        (Nil, None)

    val outputVars = context.mm.blendObjects(
      concatDef.name,
      outputDims,
      findInterLayerOutputs(context, concatDef.name, None),
      inputsVars.map(_.name).toSeq ++ inputNamesToAdd,
      outputAddresses
    )

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        concatDef,
        Seq(outputVars),
        inputsVars.toSeq
      )

    r
  }

  private def emitResizeBilinear(
      context: EmitContext,
      resizeImageDef: NodeDef
  ): EmitResult = {
    val scheduler = startLayer(Seq(resizeImageDef))

    val inputVars =
      context.mm.consumeObject(
        resizeImageDef.input(0),
        Seq(resizeImageDef.name)
      )

    val inputTemp = context.mm.allocateTempObject(
      inputVars.name,
      inputVars.dims
    )

    scheduler.emitLoad(inputVars, inputTemp)

    val outputTemp =
      if (resizeImageDef.op == "ResizeBilinear")
        emitLayerResizeBilinear(context, scheduler, resizeImageDef, inputTemp)
      else
        throw new CompilerException(
          s"${resizeImageDef.op} is not supported"
        )

    val outputVars = context.mm.allocateVarsObject(
      outputTemp.name,
      outputTemp.dims,
      findInterLayerOutputs(context, resizeImageDef.name, None)
    )

    scheduler.emitSave(outputTemp, outputVars)

    finishLayer(scheduler, context)
  }

  private def emitLayerResizeBilinear(
      context: EmitContext,
      scheduler: Scheduler,
      resizeImageDef: NodeDef,
      inputTemp: MemoryObject
  ): MemoryObject = {
    val size =
      context.mm
        .getPendingIntConst(resizeImageDef.input(1))

    val (resizeWidth, resizeHeight) =
      if (size.data.size == 2) (size.data(0), size.data(1))
      else
        throw new CompilerException(
          s"Unsupported resize image size ${size.data}"
        )

    val halfPixelCenters = resizeImageDef.attr
      .get("half_pixel_centers")
      .get
      .value
      .b
      .get

    val alignCorners = resizeImageDef.attr
      .get("align_corners")
      .get
      .value
      .b
      .get

    if (alignCorners)
      throw new CompilerException(
        s"Resize image with align corners is not supported"
      )

    val outputTemp = context.mm.allocateTempObject(
      resizeImageDef.name,
      VarsDimensions(
        inputTemp.dims.number,
        resizeHeight,
        resizeWidth,
        inputTemp.dims.channels,
      )
    )

    val (scaleX, scaleY) =
      (
        inputTemp.dims.widthVectors.toDouble / outputTemp.dims.widthVectors.toDouble,
        inputTemp.dims.heightVectors.toDouble / outputTemp.dims.heightVectors.toDouble
      )

    val lerpTemps = mutable.Map.empty[String, MemoryObject]

    def mkLerpTemp(lerp: Float) = {
      val lerpName =
        s"${resizeImageDef.name}+Lerp${lerp.toString().replaceAll("[\\.-]", "_")}"

      lerpTemps.getOrElseUpdate(
        lerpName, {
          context.mm.addPendingConst(
            lerpName,
            new TensorData(
              Shape(arch.arraySize * inputTemp.dims.channelsVectors),
              Array.fill(arch.arraySize * inputTemp.dims.channelsVectors)(
                lerp.toFloat
              ),
              DataType.DT_FLOAT
            )
          )

          val lerpConst = context.mm.getOrEmitConstObject(lerpName)
          val lerpTemp = context.mm.allocateTempObject(
            lerpConst.name,
            lerpConst.dims
          )

          scheduler.emitLoad(lerpConst, lerpTemp)

          lerpTemp
        }
      )
    }

    for (
      n <- 0 until outputTemp.dims.numberVectors;
      y <- 0 until outputTemp.dims.heightVectors;
      x <- 0 until outputTemp.dims.widthVectors
    ) {
      val (inX, inY) =
        if (halfPixelCenters)
          (
            scaleX * (x.toDouble + 0.5) - 0.5,
            scaleY * (y.toDouble + 0.5) - 0.5
          )
        else (scaleX * x.toDouble, scaleY * y.toDouble)

      val (lowX, lowY) =
        (
          math.max(math.floor(inX).toInt, 0),
          math.max(math.floor(inY).toInt, 0)
        )

      val (highX, highY) =
        (
          math.min(math.ceil(inX).toInt, inputTemp.dims.widthVectors - 1),
          math.min(math.ceil(inY).toInt, inputTemp.dims.heightVectors - 1)
        )

      val (lerpX, lerpY)   = (inX - math.floor(inX), inY - math.floor(inY))
      val (ilerpX, ilerpY) = (1 - lerpX, 1 - lerpY)

      val pixelXYs =
        Seq((lowX, lowY), (lowX, highY), (highX, lowY), (highX, highY))
      val pixelLerps =
        Seq(ilerpX * ilerpY, ilerpX * lerpY, lerpX * ilerpY, lerpX * lerpY)

      val pixelInputsTemp =
        pixelXYs.map({
          case (px, py) => {
            mkSub(
              inputTemp,
              (px + (py + n * inputTemp.dims.heightVectors) * inputTemp.dims.widthVectors) * inputTemp.dims.channelsVectors,
              VarsDimensions(inputTemp.dims.channels)
            )
          }
        })

      val lerpsTemp = pixelLerps.map(lerp => mkLerpTemp(lerp.toFloat))

      val pixelOutputTemp = mkSub(
        outputTemp,
        (x + (y + n * outputTemp.dims.heightVectors) * outputTemp.dims.widthVectors) * outputTemp.dims.channelsVectors,
        VarsDimensions(outputTemp.dims.channels)
      )

      scheduler.emitInterpolate(
        pixelInputsTemp,
        lerpsTemp,
        pixelOutputTemp
      )
    }

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        resizeImageDef,
        Seq(outputTemp),
        Seq(inputTemp)
      )

    outputTemp
  }

  private def emitPool(context: EmitContext, poolDef: NodeDef): EmitResult = {
    val scheduler = startLayer(Seq(poolDef))

    val inputVars =
      context.mm.consumeObject(poolDef.input(0), Seq(poolDef.name))

    val inputTemp = context.mm.allocateTempObject(
      inputVars.name,
      inputVars.dims
    )

    scheduler.emitLoad(inputVars, inputTemp)

    val outputTemp =
      emitLayerPool(context, scheduler, poolDef, inputTemp)

    val outputVars = context.mm.allocateVarsObject(
      outputTemp.name,
      outputTemp.dims,
      findInterLayerOutputs(context, poolDef.name, None)
    )

    scheduler.emitSave(outputTemp, outputVars)

    finishLayer(scheduler, context)
  }

  private def emitNorm(context: EmitContext, normDef: NodeDef): EmitResult = {
    val scheduler = startLayer(Seq(normDef))

    val inputVars =
      context.mm.consumeObject(normDef.input(0), Seq(normDef.name))

    val inputTemp = context.mm.allocateTempObject(
      inputVars.name,
      inputVars.dims
    )

    scheduler.emitLoad(inputVars, inputTemp)

    val outputTemp =
      emitLayerNorm(context, scheduler, normDef, inputTemp)

    val outputVars = context.mm.allocateVarsObject(
      outputTemp.name,
      outputTemp.dims,
      findInterLayerOutputs(context, normDef.name, None)
    )

    scheduler.emitSave(outputTemp, outputVars)

    finishLayer(scheduler, context)
  }

  private def emitActivate(
      context: EmitContext,
      activateDef: NodeDef
  ): EmitResult = {
    val scheduler = startLayer(Seq(activateDef))

    val inputVars =
      context.mm.consumeObject(activateDef.input(0), Seq(activateDef.name))

    val inputTemp = context.mm.allocateTempObject(
      inputVars.name,
      inputVars.dims
    )

    scheduler.emitLoad(inputVars, inputTemp)

    val outputTemp =
      emitLayerActivate(context, scheduler, activateDef, inputTemp)

    val outputVars = context.mm.allocateVarsObject(
      outputTemp.name,
      outputTemp.dims,
      findInterLayerOutputs(context, activateDef.name, None)
    )

    scheduler.emitSave(outputTemp, outputVars)

    finishLayer(scheduler, context)
  }

  private def emitAdd(context: EmitContext, addDef: NodeDef): EmitResult = {
    val scheduler = startLayer(Seq(addDef))

    val input0Vars =
      context.mm.consumeObject(addDef.input(0), Seq(addDef.name))

    val input0Temp = context.mm.allocateTempObject(
      input0Vars.name,
      input0Vars.dims
    )

    scheduler.emitLoad(input0Vars, input0Temp)

    val outputTemp =
      emitLayerAdd(context, scheduler, addDef, input0Temp)

    val outputVars = context.mm.allocateVarsObject(
      outputTemp.name,
      outputTemp.dims,
      findInterLayerOutputs(context, addDef.name, None)
    )

    scheduler.emitSave(outputTemp, outputVars)

    finishLayer(scheduler, context)
  }

  private def emitMean(context: EmitContext, meanDef: NodeDef): EmitResult = {
    val scheduler = startLayer(Seq(meanDef))

    val inputVars =
      context.mm.consumeObject(meanDef.input(0), Seq(meanDef.name))

    val inputTemp = context.mm.allocateTempObject(
      inputVars.name,
      inputVars.dims
    )

    scheduler.emitLoad(inputVars, inputTemp)

    val outputTemp =
      emitLayerMean(context, scheduler, meanDef, inputTemp)

    val outputVars = context.mm.allocateVarsObject(
      outputTemp.name,
      outputTemp.dims,
      findInterLayerOutputs(context, meanDef.name, None)
    )

    scheduler.emitSave(outputTemp, outputVars)

    finishLayer(scheduler, context)
  }

  private def emitLayerConv2D(
      context: EmitContext,
      scheduler: Scheduler,
      conv2DDef: NodeDef,
      biasDef: Option[NodeDef]
  ): MemoryObject = {
    val padding = conv2DDef.attr
      .get("padding")
      .get
      .value
      .s
      .get
      .toStringUtf8()
    val dataFormat = conv2DDef.attr
      .get("data_format")
      .get
      .value
      .s
      .get
      .toStringUtf8()

    if (dataFormat != "NHWC")
      throw new CompilerException(
        s"Unsupported data format ${dataFormat}"
      )

    if (!(padding == "SAME" || padding == "VALID"))
      throw new CompilerException(s"Unsupported padding ${padding}")

    val strides = conv2DDef.attr
      .get("strides")
      .get
      .getList
      .i

    if (strides.length != 4) {
      throw new CompilerException(
        s"Unsupported strides [${strides.mkString(", ")}]"
      )
    }

    val stridesHeight = strides(1).toInt
    val stridesWidth  = strides(2).toInt

    val (weights, bias) =
      context.mm.getOrEmitWeightsAndBiasObjects(
        conv2DDef.input(1),
        if (biasDef.isDefined) Some(biasDef.get.input(1)) else None
      )

    val inputVars =
      context.mm.consumeObject(conv2DDef.input(0), Seq(conv2DDef.name))

    val (paddingLeft, paddingTop, paddingRight, paddingBottom) =
      if (padding == "SAME") {
        val paddingWidth =
          (weights.dims.width.toDouble - 1) / 2
        val paddingHeight =
          (weights.dims.height.toDouble - 1) / 2

        (
          Math.floor(paddingWidth).toInt,
          Math.floor(paddingHeight).toInt,
          Math.ceil(paddingWidth).toInt,
          Math.ceil(paddingHeight).toInt
        )
      } else
        (0, 0, 0, 0)

    val outputTemp =
      context.mm.allocateTempObject(
        if (biasDef.isDefined) biasDef.get.name else conv2DDef.name,
        VarsDimensions(
          inputVars.dims.number,
          (
            ((paddingTop + inputVars.dims.height + paddingBottom) - weights.dims.height) /
              stridesHeight
          ) + 1,
          (
            ((paddingLeft + inputVars.dims.width + paddingRight) - weights.dims.width) /
              stridesWidth
          ) + 1,
          weights.dims.channelsOut
        )
      )

    for (
      k <- 0 until weights.dims.heightVectors;
      l <- 0 until weights.dims.widthVectors
    ) {
      val pixelWeights = mkSub(
        weights,
        (l + k * weights.dims.widthVectors) * weights.dims.channelsInVectors * weights.dims.channelsOutVectors,
        ConstsDimensions(
          weights.dims.channelsIn,
          weights.dims.channelsOut
        )
      )

      val withBias = (l == 0) && (k == 0)

      val pixelPairs =
        for (
          n <- 0 until outputTemp.dims.numberVectors;
          i <- 0 until outputTemp.dims.heightVectors;
          j <- 0 until outputTemp.dims.widthVectors
        ) yield {
          val pixelOutputTemp = mkSub(
            outputTemp,
            (j + (i + n * outputTemp.dims.heightVectors) * outputTemp.dims.widthVectors) * outputTemp.dims.channelsVectors,
            VarsDimensions(1, outputTemp.dims.channels)
          )

          val p = k + i * stridesHeight
          val q = l + j * stridesWidth

          if (
            q >= paddingLeft && p >= paddingTop && q < paddingLeft + inputVars.dims.widthVectors && p < paddingTop + inputVars.dims.heightVectors
          ) {
            val pixelInputVars = mkSub(
              inputVars,
              ((q - paddingLeft) + (p - paddingTop + n * inputVars.dims.heightVectors) * inputVars.dims.widthVectors) * inputVars.dims.channelsVectors,
              VarsDimensions(1, inputVars.dims.channels)
            )

            MemoryOptionalInputOutputObjects(
              Some(pixelInputVars),
              pixelOutputTemp
            )
          } else
            MemoryOptionalInputOutputObjects(None, pixelOutputTemp)
        }

      scheduler.emitMatMul(
        pixelWeights,
        if (withBias) bias else None,
        pixelPairs
      )
    }

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        biasDef.getOrElse(conv2DDef),
        Seq(outputTemp),
        Seq(inputVars),
        Seq(("Weights", weights)) ++ (if (bias.isDefined)
                                        Seq(("Bias", bias.get))
                                      else Nil)
      )

    outputTemp
  }

  private def emitLayerMatMul(
      context: EmitContext,
      scheduler: Scheduler,
      matMulDef: NodeDef,
      biasDef: Option[NodeDef]
  ): MemoryObject = {
    val (weights, bias) =
      context.mm.getOrEmitWeightsAndBiasObjects(
        matMulDef.input(1),
        if (biasDef.isDefined) Some(biasDef.get.input(1)) else None
      )

    val inputVars =
      context.mm.consumeObject(matMulDef.input(0), Seq(matMulDef.name))

    val outputTemp =
      context.mm.allocateTempObject(
        if (biasDef.isDefined) biasDef.get.name else matMulDef.name,
        VarsDimensions(
          inputVars.dims.number,
          weights.dims.width
        )
      )

    val pairs = Seq(
      MemoryOptionalInputOutputObjects(Some(inputVars), outputTemp)
    )

    scheduler.emitMatMul(
      weights,
      bias,
      pairs
    )

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        biasDef.getOrElse(matMulDef),
        Seq(outputTemp),
        Seq(inputVars),
        Seq(("Weights", weights)) ++ (if (bias.isDefined)
                                        Seq(("Bias", bias.get))
                                      else Nil)
      )

    outputTemp
  }

  private def emitLayerPool(
      context: EmitContext,
      scheduler: Scheduler,
      poolDef: NodeDef,
      inputTemp: MemoryObject
  ): MemoryObject = {
    val padding = poolDef.attr
      .get("padding")
      .get
      .value
      .s
      .get
      .toStringUtf8()

    val dataFormat = poolDef.attr
      .get("data_format")
      .get
      .value
      .s
      .get
      .toStringUtf8()

    if (dataFormat != "NHWC")
      throw new CompilerException(
        s"Unsupported data format ${dataFormat}"
      )

    if (!(padding == "SAME" || padding == "VALID"))
      throw new CompilerException(s"Unsupported padding ${padding}")

    val kSize = poolDef.attr
      .get("ksize")
      .get
      .getList
      .i

    if (kSize.length != 4) {
      throw new CompilerException(
        s"Unsupported ksize [${kSize.mkString(", ")}]"
      )
    }
    val kHeight = kSize(1).toInt
    val kWidth  = kSize(2).toInt

    val strides = poolDef.attr
      .get("strides")
      .get
      .getList
      .i

    if (strides.length != 4) {
      throw new CompilerException(
        s"Unsupported strides [${strides.mkString(", ")}]"
      )
    }

    val stridesHeight = strides(1).toInt
    val stridesWidth  = strides(2).toInt

    val (paddingLeft, paddingTop, paddingRight, paddingBottom) =
      (0, 0, 0, 0)

    val outputTemp = context.mm.allocateTempObject(
      poolDef.name,
      VarsDimensions(
        inputTemp.dims.number,
        (
          ((paddingTop + inputTemp.dims.height + paddingBottom) - kHeight) /
            stridesHeight
        ) + 1,
        (
          ((paddingLeft + inputTemp.dims.width + paddingRight) - kWidth) /
            stridesWidth
        ) + 1,
        inputTemp.dims.channels
      )
    )

    val multiplierTemp = if (poolDef.op == "AvgPool") {
      val multiplierName = s"${poolDef.name}+Multiplier"

      context.mm.addPendingConst(
        multiplierName,
        new TensorData(
          Shape(arch.arraySize),
          Array.fill(arch.arraySize)(
            1f / (stridesHeight * stridesWidth).toFloat
          ),
          DataType.DT_FLOAT
        )
      )

      val multiplierConst = context.mm.getOrEmitConstObject(multiplierName)
      val multiplierTemp = context.mm.allocateTempObject(
        multiplierConst.name,
        multiplierConst.dims
      )

      scheduler.emitLoad(multiplierConst, multiplierTemp)

      Some(multiplierTemp)
    } else None

    for (
      n <- 0 until outputTemp.dims.numberVectors;
      i <- 0 until outputTemp.dims.heightVectors;
      j <- 0 until outputTemp.dims.widthVectors
    ) {
      val pixelOutputTemp = mkSub(
        outputTemp,
        (j + (i + n * outputTemp.dims.heightVectors) * outputTemp.dims.widthVectors) * outputTemp.dims.channelsVectors,
        VarsDimensions(outputTemp.dims.channels)
      )

      val pixelInputsTemp =
        for (
          k <- 0 until kHeight;
          l <- 0 until kWidth
        ) yield {
          val p = k + i * stridesHeight
          val q = l + j * stridesWidth

          mkSub(
            inputTemp,
            (q + (p + n * inputTemp.dims.heightVectors) * inputTemp.dims.widthVectors) * inputTemp.dims.channelsVectors,
            VarsDimensions(inputTemp.dims.channels)
          )
        }

      scheduler.emitPool(
        poolDef.op,
        pixelInputsTemp,
        pixelOutputTemp,
        multiplierTemp
      )
    }

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        poolDef,
        Seq(outputTemp),
        Seq(inputTemp)
      )

    outputTemp
  }

  private def emitLayerActivate(
      context: EmitContext,
      scheduler: Scheduler,
      activateDef: NodeDef,
      inputTemp: MemoryObject,
  ): MemoryObject = {
    val outputTemp = context.mm.allocateTempObject(
      activateDef.name,
      inputTemp.dims
    )

    activateDef.op match {
      case "Relu" =>
        scheduler.emitRelu(
          inputTemp,
          outputTemp
        )

      case "Softmax" =>
        scheduler.emitSoftmax(
          inputTemp,
          outputTemp
        )

      case "LeakyRelu" => {
        val alpha = activateDef.attr
          .get("alpha")
          .get
          .value
          .f
          .get

        val alphaName = s"${activateDef.name}+Alpha"

        context.mm.addPendingConst(
          alphaName,
          new TensorData(
            Shape(arch.arraySize),
            Array.fill(arch.arraySize)(alpha),
            DataType.DT_FLOAT
          )
        )

        val alphaConst = context.mm.getOrEmitConstObject(alphaName)
        val alphaTemp = context.mm.allocateTempObject(
          alphaConst.name,
          alphaConst.dims
        )

        scheduler.emitLoad(alphaConst, alphaTemp)
        scheduler.emitLeakyRelu(
          inputTemp,
          alphaTemp,
          outputTemp
        )
      }
    }

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        activateDef,
        Seq(outputTemp),
        Seq(inputTemp)
      )

    outputTemp
  }

  private def emitLayerNorm(
      context: EmitContext,
      scheduler: Scheduler,
      normDef: NodeDef,
      inputTemp: MemoryObject,
  ): MemoryObject = {
    val dataFormat = normDef.attr
      .get("data_format")
      .get
      .value
      .s
      .get
      .toStringUtf8()

    if (dataFormat != "NHWC")
      throw new CompilerException(
        s"Unsupported data format ${dataFormat}"
      )

    val epsilon = normDef.attr
      .get("epsilon")
      .get
      .value
      .f
      .get

    val scale =
      context.mm
        .getPendingFloatConst(normDef.input(1))
    val offset =
      context.mm
        .getPendingFloatConst(normDef.input(2))
    val mean =
      context.mm
        .getPendingFloatConst(normDef.input(3))
    val variance =
      context.mm
        .getPendingFloatConst(normDef.input(4))

    val size = scale.shape(0)

    val inferenceScale  = Array.fill(size)(0f)
    val inferenceOffset = Array.fill(size)(0f)

    for (i <- 0 until size) {
      inferenceScale(i) =
        ((1 / Math.sqrt(variance.data(i) + epsilon)) * scale.data(i)).toFloat
      inferenceOffset(i) =
        (offset.data(i) - mean.data(i) * inferenceScale(i)).toFloat
    }

    val scaleName  = s"${normDef.name}+Scale"
    val offsetName = s"${normDef.name}+Offset"

    context.mm.addPendingConst(
      scaleName,
      new TensorData(scale.shape, inferenceScale, scale.dtype)
    )
    context.mm.addPendingConst(
      offsetName,
      new TensorData(scale.shape, inferenceOffset, scale.dtype)
    )

    val scaleConst  = context.mm.getOrEmitConstObject(scaleName)
    val offsetConst = context.mm.getOrEmitConstObject(offsetName)

    val scaleTemp = context.mm.allocateTempObject(
      scaleConst.name,
      scaleConst.dims
    )

    val offsetTemp = context.mm.allocateTempObject(
      offsetConst.name,
      offsetConst.dims
    )

    scheduler.emitLoad(scaleConst, scaleTemp)
    scheduler.emitLoad(offsetConst, offsetTemp)

    val outputTemp = context.mm.allocateTempObject(
      normDef.name,
      inputTemp.dims
    )

    for (
      n <- 0 until outputTemp.dims.numberVectors;
      i <- 0 until outputTemp.dims.heightVectors;
      j <- 0 until outputTemp.dims.widthVectors
    ) {
      val offset =
        (((n * inputTemp.dims.heightVectors + i) * inputTemp.dims.widthVectors) + j) * outputTemp.dims.channelsVectors
      val dims            = VarsDimensions(outputTemp.dims.channels)
      val pixelInputTemp  = mkSub(inputTemp, offset, dims)
      val pixelOutputTemp = mkSub(outputTemp, offset, dims)

      scheduler.emitNorm(
        pixelInputTemp,
        scaleTemp,
        offsetTemp,
        pixelOutputTemp
      )
    }

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        normDef,
        Seq(outputTemp),
        Seq(inputTemp),
        Seq(("Scale", scaleTemp), ("Offset", offsetTemp))
      )

    outputTemp
  }

  private def emitLayerAdd(
      context: EmitContext,
      scheduler: Scheduler,
      addDef: NodeDef,
      input0Temp: MemoryObject,
  ): MemoryObject = {
    val outputTemp = context.mm.allocateTempObject(
      addDef.name,
      input0Temp.dims
    )

    val input1Name =
      if (addDef.input(0) == input0Temp.name) addDef.input(1)
      else addDef.input(0)
    val input1Vars =
      context.mm.consumeObject(input1Name, Seq(addDef.name))

    scheduler.emitAdd(
      input0Temp,
      input1Vars,
      outputTemp
    )

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        addDef,
        Seq(outputTemp),
        Seq(input1Vars, input0Temp)
      )

    outputTemp
  }

  private def emitLayerMean(
      context: EmitContext,
      scheduler: Scheduler,
      meanDef: NodeDef,
      inputTemp: MemoryObject,
  ): MemoryObject = {
    val reductionIndices =
      context.mm
        .getPendingIntConst(meanDef.input(1))

    if (
      reductionIndices.data.size != 2 || reductionIndices.data(
        0
      ) != 1 || reductionIndices.data(1) != 2
    )
      throw new CompilerException("Only channel mean is supported")

    val inputDims  = inputTemp.dims
    val outputDims = VarsDimensions(inputDims.number, inputDims.channels)

    val outputTemp = context.mm.allocateTempObject(
      meanDef.name,
      outputDims
    )

    val multiplierName = s"${meanDef.name}+Multiplier"

    context.mm.addPendingConst(
      multiplierName,
      new TensorData(
        Shape(arch.arraySize),
        Array.fill(arch.arraySize)(
          1f / (inputDims.height * inputDims.width).toFloat
        ),
        DataType.DT_FLOAT
      )
    )

    val multiplierConst = context.mm.getOrEmitConstObject(multiplierName)
    val multiplierTemp = context.mm.allocateTempObject(
      multiplierConst.name,
      multiplierConst.dims
    )

    scheduler.emitLoad(multiplierConst, multiplierTemp)

    for (n <- 0 until inputDims.numberVectors) {
      val dims = VarsDimensions(inputDims.channels)
      val pixelOutputTemp =
        mkSub(outputTemp, n * inputDims.channelsVectors, dims)

      val pixelInputsTemp =
        for (
          i <- 0 until inputDims.heightVectors;
          j <- 0 until inputDims.widthVectors
        ) yield {
          val offset =
            (((n * inputDims.heightVectors + i) * inputDims.widthVectors) + j) * inputDims.channelsVectors

          mkSub(inputTemp, offset, dims)
        }

      scheduler.emitPool(
        "AvgPool",
        pixelInputsTemp,
        pixelOutputTemp,
        Some(multiplierTemp)
      )
    }

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        meanDef,
        Seq(outputTemp),
        Seq(inputTemp)
      )

    outputTemp
  }

  private def mkSub(
      obj: MemoryObject,
      offset: Int,
      dims: MemoryDimensions = VarsDimensions(arch.arraySize)
  ): MemoryObject =
    obj.mkSub(
      obj.name,
      offset,
      dims
    )

  private def getOrEmitAdjustmentWeights(
      context: EmitContext,
      offsetPairs: IndexedSeq[(Int, Int)]
  ) = {
    val suffix = offsetPairs
      .map({ case (offset0, offset1) => s"${offset0}_${offset1}" })
      .mkString("__")

    val weightsName = s"Shared/$suffix/ReshapeWeights"

    if (!context.mm.hasPendingFloatConst(weightsName)) {
      val weightsData = Array.fill(arch.arraySize * arch.arraySize)(0f)

      for ((outputOffset, inputOffset) <- offsetPairs) {
        weightsData(
          inputOffset * arch.arraySize + outputOffset
        ) = 1f
      }

      context.mm.addPendingConst(
        weightsName,
        new TensorData(
          Shape(arch.arraySize, arch.arraySize),
          weightsData,
          DataType.DT_FLOAT
        )
      )
    }

    val (weightsConst, _) =
      context.mm
        .getOrEmitWeightsAndBiasObjects(weightsName, None)

    weightsConst
  }

  private def splitName(name: String, i: Int) =
    if (i == 0) name else s"${name}:$i"
}
