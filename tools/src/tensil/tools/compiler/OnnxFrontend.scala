/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io.OutputStream

import scala.collection.mutable

import onnx.onnx.{NodeProto, ModelProto, TensorProto, ValueInfoProto}

import _root_.tensil.tools.{
  CompilerException,
  TracepointCondition,
  CompilerOptions
}
import _root_.tensil.tools.data.{Shape, TensorData}
import _root_.tensil.tools.util
import _root_.tensil.{TablePrinter, Architecture}
import com.google.protobuf.CodedInputStream
import onnx.onnx.TensorShapeProto

object OnnxFrontend {
  val ModelFileNameExtension = "onnx"
}

class OnnxFrontend(
    modelProto: ModelProto,
    arch: Architecture,
    graphStream: Option[OutputStream],
    options: CompilerOptions
) extends Frontend {

  private object VarsDimensions {
    def apply(
        number: Int,
        channel: Int,
        height: Int,
        width: Int,
    ): MemoryDimensions =
      MemoryDimensions(
        arraySize = arch.arraySize,
        "NCHW",
        "NHWC",
        isWeights = false,
        dimensions = Vector(number, channel, height, width)
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
        "CoCHW",
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

  private val nodeProtos            = mutable.Map.empty[String, NodeProto]
  private val tensorProtos          = mutable.Map.empty[String, TensorProto]
  private val inputValueInfoProtos  = mutable.Map.empty[String, ValueInfoProto]
  private val outputValueInfoProtos = mutable.Map.empty[String, ValueInfoProto]

  private val inputNodeNames =
    mutable.Map.empty[String, mutable.ArrayBuffer[String]]
  private val outputNodeNames =
    mutable.Map.empty[String, mutable.ArrayBuffer[String]]

  for (tensorProto <- modelProto.graph.get.initializer)
    tensorProtos(tensorProto.name.get) = tensorProto

  for (valueInfoProto <- modelProto.graph.get.input)
    inputValueInfoProtos(valueInfoProto.name.get) = valueInfoProto

  for (valueInfoProto <- modelProto.graph.get.output)
    outputValueInfoProtos(valueInfoProto.name.get) = valueInfoProto

  for (nodeProto <- modelProto.graph.get.node) {
    val nodeName = nodeProto.name.get

    nodeProtos(nodeName) = nodeProto

    for (inputName <- nodeProto.input) {
      inputNodeNames.getOrElseUpdate(
        inputName,
        mutable.ArrayBuffer.empty[String]
      ) += nodeName
    }

    for (outputName <- nodeProto.output) {
      outputNodeNames.getOrElseUpdate(
        outputName,
        mutable.ArrayBuffer.empty[String]
      ) += nodeName
    }
  }

  val graphPrinter =
    graphStream.map(
      new OnnxFrontendGraphPrinter(
        _,
        "model",
        arch.arraySize,
        outputNodeNames.mapValues(_.toArray).toMap
      )
    )

  /*
   * Traverse function takes the names of the outputs representing
   * the result of the computation. Then, it uses `outputNodeNames` to recursively
   * traverse the computation graph. By doing this, it constructs the list of node
   * names that reflect the data flow dependencies.
   */
  def traverse(outputNames: Seq[String]): Seq[String] =
    recursiveTraverse(Seq.empty[String], outputNames)

  private def recursiveTraverse(
      prev: Seq[String],
      outputNames: Seq[String]
  ): Seq[String] = {
    outputNames
      .map(outputNodeNames(_))
      .flatten
      .distinct
      .foldLeft(prev)((prev, nodeName) => {
        recursiveTraverse(
          nodeName +: prev,
          nodeProtos(nodeName).input.toSet
            .diff(tensorProtos.keySet ++ inputValueInfoProtos.keySet)
            .toSeq
        )
      })
      .distinct
  }

  /*
   * Rewrite function takes the list of node names previously
   * constructed by the traverse function and maps it to node
   * definition list. Then function inspects a node definition
   * from the definition list in the order that reflects the
   * data flow dependencies. This inspection results in:
   *
   *   a) skipping the definition,
   *   b) taking additional node definition from the definition
   *      list to inspect in combination with the current node
   *      definition (see `rewriteLayer`), or
   *   c) adding a function closure to the emit list.
   *
   * The emit list contains functions that take an emit context and
   * captures all necessary backend and memory managment operations to
   * emit the instructions representing the rewritten node(s).
   * The recursion continues until the definition list is empty.
   */
  def rewrite(program: Seq[String]): Seq[Emitter] = {
    (emitInput(_)) +: recursiveRewrite(
      program.map(nodeName => nodeProtos(nodeName))
    ).reverse :+ (emitOutput(_))
  }

  private def recursiveRewrite(
      protos: Seq[NodeProto],
      emitters: Seq[Emitter] = Nil
  ): Seq[Emitter] = {
    protos match {
      case Nil => emitters
      case nodeProto :: remainingProtos =>
        nodeProto.opType.get match {
          case "MatMul" | "Gemm" | "Conv" =>
            rewriteLayer(remainingProtos, nodeProto, emitters)
          case "Reshape" =>
            rewriteSimple(remainingProtos, emitReshape(_, nodeProto), emitters)
          case "Split" =>
            rewriteSimple(remainingProtos, emitSplit(_, nodeProto), emitters)
          case "Concat" =>
            rewriteSimple(remainingProtos, emitConcat(_, nodeProto), emitters)
          case "Resize" =>
            rewriteSimple(
              remainingProtos,
              emitResize(_, nodeProto),
              emitters
            )
          case "MaxPool" | "AveragePool" =>
            rewriteSimple(remainingProtos, emitPool(_, nodeProto), emitters)
          case "BatchNormalization" =>
            rewriteSimple(remainingProtos, emitNorm(_, nodeProto), emitters)
          case "Relu" | "Softmax" | "LeakyRelu" =>
            rewriteSimple(remainingProtos, emitActivate(_, nodeProto), emitters)
          case "Add" =>
            rewriteSimple(remainingProtos, emitAdd(_, nodeProto), emitters)
          case "Transpose" =>
            rewriteSimple(
              remainingProtos,
              emitTranspose(_, nodeProto),
              emitters
            )
          case "Squeeze" =>
            rewriteSimple(
              remainingProtos,
              emitSqueeze(_, nodeProto),
              emitters
            )
          case "Shape" =>
            rewriteSimple(remainingProtos, emitShape(_, nodeProto), emitters)
          case "Slice" =>
            rewriteSimple(remainingProtos, emitSlice(_, nodeProto), emitters)
          case "Cast" =>
            rewriteSimple(remainingProtos, emitCast(_, nodeProto), emitters)
          case "Div" =>
            rewriteSimple(remainingProtos, emitDiv(_, nodeProto), emitters)
          case "Pad" =>
            rewriteSimple(remainingProtos, emitPad(_, nodeProto), emitters)
          case "Constant" =>
            rewriteSimple(remainingProtos, emitConstant(_, nodeProto), emitters)
          case "GlobalAveragePool" =>
            rewriteSimple(
              remainingProtos,
              emitGlobalPool(_, nodeProto),
              emitters
            )
          case op =>
            throw new CompilerException(
              s"Unsupported op ${op} (${nodeProto.name.get})"
            )
        }
    }
  }

  private def rewriteLayer(
      protos: Seq[NodeProto],
      startNodeProto: NodeProto,
      emitters: Seq[Emitter]
  ) =
    recursiveRewriteLayer(
      Seq(
        Set("Add"),
        Set("BatchNormalization"),
        Set("Relu", "Softmax", "LeakyRelu"),
        Set("MaxPool", "AveragePool")
      ),
      protos,
      Seq(Some(startNodeProto)),
      emitters
    )

  /*
   * This function takes `layerStepOps`, which describes
   * the pattern to which we expect nodes to adhere in order
   * to form a layer. The initial and the only required node is
   * matched in `recursiveRewrite` to be either `MatMul`, `Gemm` or
   * `Conv`. This node is followed by "layer step operations"
   * where each step can optionally be one of the operations
   * included in the set.
   *
   * The function recurses by taking one step at the time and
   * taking corresponding TF nodes until `layerStepOps` is empty
   * and `layerProtos` is filled with matching TF nodes.
   */
  private def recursiveRewriteLayer(
      layerStepOps: Seq[Set[String]],
      protos: Seq[NodeProto],
      layerProtos: Seq[Option[NodeProto]],
      emitters: Seq[Emitter]
  ): Seq[Emitter] = {
    def doIt() = {
      val (poolProto :: activateProto :: normProto :: addProto :: nodeProto :: Nil) =
        Seq.fill[Option[NodeProto]](5 - layerProtos.size)(None) ++ layerProtos

      val emitter = doRewriteLayer(
        nodeProto.get,
        addProto,
        normProto,
        activateProto,
        poolProto
      )

      recursiveRewrite(protos, emitter +: emitters)
    }

    layerStepOps match {
      case Nil => doIt()

      case stepOps :: layerStepOps =>
        protos match {
          case Nil => doIt()

          case nodeProto :: protos =>
            val prevProto = layerProtos.find(_.isDefined).get.get

            if (
              stepOps.contains(nodeProto.opType.get) &&
              !nodeProto.input.toSet.intersect(prevProto.output.toSet).isEmpty
            )
              recursiveRewriteLayer(
                layerStepOps,
                protos,
                Some(nodeProto) +: layerProtos,
                emitters
              )
            else
              recursiveRewriteLayer(
                layerStepOps,
                nodeProto +: protos,
                None +: layerProtos,
                emitters
              )
        }
    }
  }

  private def rewriteSimple(
      protos: Seq[NodeProto],
      emitter: Emitter,
      emitters: Seq[Emitter]
  ): Seq[Emitter] =
    recursiveRewrite(protos, emitter +: emitters)

  private var layerIndex = 0

  private def startLayer(nodeProtos: Seq[NodeProto]): Scheduler = {
    val name =
      s"LAYER $layerIndex (${nodeProtos.map(_.name.get).mkString(",")})"

    layerIndex += 1

    new Scheduler(
      name,
      arch,
      options
    )
  }

  /*private def finishLayer(scheduler: Scheduler, context: EmitContext) = {
    scheduler.saveGraph(s"onnx_layer_${layerIndex - 1}.tgraph")
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
      nodeProto: NodeProto,
      addProto: Option[NodeProto],
      normProto: Option[NodeProto],
      activateProto: Option[NodeProto],
      poolProto: Option[NodeProto]
  ): Emitter =
    (context: EmitContext) => {
      if (graphPrinter.isDefined)
        graphPrinter.get.startLayer(s"layer_$layerIndex")

      val scheduler = startLayer(
        Seq(
          Some(nodeProto),
          addProto,
          normProto,
          activateProto,
          poolProto
        )
          .filter(_.isDefined)
          .map(_.get)
      )

      val (consumers, nodeName) =
        (
          findInterLayerOutputs(
            context,
            nodeProto.output(0),
            addProto.orElse(normProto.orElse(activateProto.orElse(poolProto)))
          ),
          nodeProto.name.get
        )

      val matMulTemp =
        if (nodeProto.opType.get == "MatMul")
          emitLayerMatMul(
            context,
            scheduler,
            nodeProto
          )
        else if (nodeProto.opType.get == "Gemm")
          emitLayerGemm(
            context,
            scheduler,
            nodeProto
          )
        else
          emitLayerConv(
            context,
            scheduler,
            nodeProto
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

      val addTemp = if (addProto.isDefined) {
        val outputTemp = emitLayerAdd(
          context,
          scheduler,
          addProto.get,
          matMulTemp
        )

        emitSaveIfConsumed(
          outputTemp,
          findInterLayerOutputs(
            context,
            addProto.get.output(0),
            normProto.orElse(activateProto.orElse(poolProto))
          )
        )

        outputTemp

      } else matMulTemp

      val normTemp = if (normProto.isDefined) {
        val outputTemp = emitLayerNorm(
          context,
          scheduler,
          normProto.get,
          addTemp
        )

        emitSaveIfConsumed(
          outputTemp,
          findInterLayerOutputs(
            context,
            normProto.get.output(0),
            activateProto.orElse(poolProto)
          )
        )

        outputTemp

      } else addTemp

      val activateTemp =
        if (activateProto.isDefined) {
          val outputTemp = emitLayerActivate(
            context,
            scheduler,
            activateProto.get,
            normTemp
          )

          emitSaveIfConsumed(
            outputTemp,
            findInterLayerOutputs(
              context,
              activateProto.get.output(0),
              poolProto
            )
          )

          outputTemp

        } else normTemp

      if (poolProto.isDefined) {
        val outputTemp =
          emitLayerPool(context, scheduler, poolProto.get, activateTemp)

        emitSaveIfConsumed(
          outputTemp,
          findInterLayerOutputs(context, poolProto.get.output(0), None)
        )

      }

      if (graphPrinter.isDefined)
        graphPrinter.get.endLayer()

      finishLayer(scheduler, context)
    }

  private def findInterLayerOutputs(
      context: EmitContext,
      outputName: String,
      nextNode: Option[NodeProto]
  ): Seq[String] =
    if (context.outputNames.contains(outputName))
      Seq("pin") // TODO: we need another mechanism to pin output objects
    else
      (inputNodeNames
        .get(outputName)
        .map(_.toSet)
        .getOrElse(Set.empty) -- (if (nextNode.isDefined)
                                    Set(nextNode.get.name.get)
                                  else Seq.empty)).toSeq

  private def emitInput(context: EmitContext): EmitResult = {
    for ((name, valueInfoProto) <- inputValueInfoProtos) {
      val shape = Shape(
        valueInfoProto.`type`.get.value.tensorType.get.shape.get.dim
          .map(_.value.dimValue.get.toInt)
          .toArray
      )

      val consumers = inputNodeNames(name)

      val dims = if (consumers.size == 1) {
        val consumerProto = nodeProtos(consumers(0))

        if (consumerProto.opType.get == "Transpose") {
          val permAttr = getAttr(consumerProto, "perm").get

          require(permAttr.`type`.get.isInts)

          val permutationOrder = permAttr.ints.map(_.toInt)

          if (permutationOrder == Seq(0, 3, 1, 2))
            /* When the input is followed by Transpose with (0, 3, 1, 2)
             * permutation we assume the model was coverted from TF, which
             * implies NHWC dimensions. If these assumptions start breaking
             * we will have to explicitly specify input dimensions.
             */

            MemoryDimensions(
              arraySize = arch.arraySize,
              "NHWC",
              "NHWC",
              isWeights = false,
              dimensions = shape.toVector
            )
          else
            throw new CompilerException(
              s"Initial transpose with permutation (${permutationOrder.mkString(",")}) is not supported"
            )

        } else
          VarsDimensions(shape)
      } else
        VarsDimensions(shape)

      val outputVars = context.mm.emitInputObject(
        name,
        dims,
        consumers
      )

      if (graphPrinter.isDefined) {
        graphPrinter.get.printInputPost(outputVars)
      }
    }

    None
  }

  private def emitTranspose(
      context: EmitContext,
      transposeProto: NodeProto
  ): EmitResult = {
    val permAttr = getAttr(transposeProto, "perm").get

    require(permAttr.`type`.get.isInts)

    val permutationOrder = permAttr.ints.map(_.toInt)

    val inputVars =
      context.mm
        .consumeObject(transposeProto.input(0), Seq(transposeProto.name.get))

    val outputDims = inputVars.dims.transform(permutationOrder)

    val outputVars = context.mm.blendObjects(
      transposeProto.output(0),
      outputDims,
      findInterLayerOutputs(context, transposeProto.output(0), None),
      Seq(inputVars.name),
      inputVars.span
    )

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        transposeProto,
        Seq(outputVars),
        Seq(inputVars)
      )

    None
  }

  private def emitSqueeze(
      context: EmitContext,
      squeezeProto: NodeProto
  ): EmitResult = {
    val axesAttr = getAttr(squeezeProto, "axes").get

    require(axesAttr.`type`.get.isInts)

    val axes = axesAttr.ints

    if (axes.size != 2 || axes(0) != 2 || axes(1) != 3)
      throw new CompilerException("Only 4D HW squeeze is supported")

    val inputVars =
      context.mm
        .consumeObject(squeezeProto.input(0), Seq(squeezeProto.name.get))

    val inputDims = inputVars.dims
    val outputDims =
      VarsDimensions(inputDims.number, inputDims.channels)

    val outputVars = context.mm.blendObjects(
      squeezeProto.output(0),
      outputDims,
      findInterLayerOutputs(context, squeezeProto.output(0), None),
      Seq(inputVars.name),
      inputVars.span
    )

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        squeezeProto,
        Seq(outputVars),
        Seq(inputVars)
      )

    None
  }

  private def emitShape(
      context: EmitContext,
      shapeProto: NodeProto
  ): EmitResult = {
    val inputVars =
      context.mm
        .consumeObject(shapeProto.input(0), Seq(shapeProto.name.get))

    context.mm.addPendingConst(
      shapeProto.output(0),
      new TensorData(
        Shape(inputVars.dims.order),
        Seq(
          inputVars.dims.number,
          inputVars.dims.channels,
          inputVars.dims.height,
          inputVars.dims.width
        ).map(_.toLong),
        org.tensorflow.framework.types.DataType.DT_INT64
      )
    )

    None
  }

  private def emitSlice(
      context: EmitContext,
      sliceProto: NodeProto
  ): EmitResult = {
    val input = context.mm.getPendingConst(
      org.tensorflow.framework.types.DataType.DT_INT64,
      sliceProto.input(0)
    )

    val starts =
      context.mm
        .getPendingLongConst(sliceProto.input(1))
        .asInstanceOf[TensorData[Long]]
        .as1D
        .map(_.toInt)
        .toArray

    val ends =
      context.mm
        .getPendingLongConst(sliceProto.input(2))
        .asInstanceOf[TensorData[Long]]
        .as1D
        .map(_.toInt)
        .toArray

    val axes =
      context.mm
        .getPendingLongConst(sliceProto.input(3))
        .asInstanceOf[TensorData[Long]]
        .as1D
        .map(_.toInt)
        .toArray

    context.mm.addPendingConst(
      sliceProto.output(0),
      new TensorData(
        Shape(),
        input.as1D.slice(starts(0).toInt, Int.MaxValue),
        org.tensorflow.framework.types.DataType.DT_INT64
      )
    )

    None
  }

  private def emitCast(
      context: EmitContext,
      castProto: NodeProto
  ): EmitResult = {
    val input =
      context.mm.getPendingLongConst(castProto.input(0))
    val castedData = input.data.map(_.toFloat)

    context.mm.addPendingConst(
      castProto.output(0),
      new TensorData(
        input.shape,
        castedData,
        org.tensorflow.framework.types.DataType.DT_FLOAT
      )
    )

    None
  }

  private def emitDiv(
      context: EmitContext,
      divProto: NodeProto
  ): EmitResult = {
    val a = getTensorData(tensorProtos(divProto.input(0)))
      .asInstanceOf[TensorData[Float]]

    val b =
      context.mm
        .getPendingFloatConst(divProto.input(1))
        .asInstanceOf[TensorData[Float]]

    val c = a.data.zip(b.data).map { case (a, b) => a / b }

    context.mm.addPendingConst(
      divProto.output(0),
      new TensorData(
        a.shape,
        c,
        org.tensorflow.framework.types.DataType.DT_FLOAT
      )
    )

    None
  }

  private def emitConstant(
      context: EmitContext,
      constantProto: NodeProto
  ): EmitResult = {
    val valueAttr = getAttr(constantProto, "value").get

    require(valueAttr.`type`.get.isTensor)

    val value = valueAttr.t.get

    context.mm.addPendingConst(
      constantProto.output(0),
      getTensorData(value)
    )

    None
  }

  private def emitPad(context: EmitContext, padProto: NodeProto): EmitResult = {
    val inputVars =
      context.mm.consumeObject(padProto.input(0), Seq(padProto.name.get))

    val modeAttr = getAttr(padProto, "mode")

    val mode = if (modeAttr.isDefined) {
      require(modeAttr.get.`type`.get.isString)

      modeAttr.get.s.get.toStringUtf8()
    } else
      "constant"

    val padsAttr = getAttr(padProto, "pads").get

    require(padsAttr.`type`.get.isInts)

    val pads = padsAttr.ints

    if (pads.size != 8 || inputVars.dims.order != 4)
      throw new CompilerException("Only 4D padding is supported")

    if (mode != "constant" && mode != "edge" && mode != "reflect")
      throw new CompilerException(s"Padding with ${mode} is not supported")

    val (paddingTop, paddingLeft, paddingBottom, paddingRight) =
      pads.map(_.toInt) match {
        case Seq(0, 0, t, l, 0, 0, b, r) =>
          (t, l, b, r)
        case _ =>
          throw new CompilerException("Only height/width padding is supported")
      }

    val paddedDims = VarsDimensions(
      inputVars.dims.number,
      inputVars.dims.channels,
      paddingTop + inputVars.dims.height + paddingBottom,
      paddingLeft + inputVars.dims.width + paddingRight
    )

    val paddingSize =
      (inputVars.dims.heightVectors * (paddingLeft + paddingRight) +
        paddedDims.widthVectors * (paddingTop + paddingBottom)) * inputVars.dims.numberVectors * inputVars.dims.channelsVectors

    val paddingName = s"${padProto.name.get}+Padding"

    // TODO: implement reflect and edge

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
      padProto.output(0),
      paddedDims,
      findInterLayerOutputs(context, padProto.output(0), None),
      Seq(inputVars.name, paddingVars.name),
      paddedAddresses.toArray
    )

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        padProto,
        Seq(outputVars),
        Seq(inputVars)
      )

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

  private def emitReshape(
      context: EmitContext,
      reshapeProto: NodeProto
  ): EmitResult = {
    val inputVars =
      context.mm
        .consumeObject(reshapeProto.input(0), Seq(reshapeProto.name.get))
    val inputDims = inputVars.dims

    val shape = getTensorData(tensorProtos(reshapeProto.input(1)))
      .asInstanceOf[TensorData[Long]]
      .as1D
      .map(_.toInt)
      .toArray

    var pixelDims = VarsDimensions(1, arch.arraySize)
    val outputDims = VarsDimensions(Shape(if (shape.exists(_ == -1)) {
      val inferred = inputDims.sizeScalars / shape.filter(_ != -1).product
      shape.map(d => if (d == -1) inferred else d)
    } else shape))

    if (inputDims.sizeScalars != outputDims.sizeScalars)
      throw new CompilerException("Scalar sizes must match for reshape")

    val indexesAndOffsetsPairs =
      if (inputDims.order == 2 && outputDims.order == 4) {
        // when reshaping from 1D to 4D, a NCHW to NHWC permute(transpose)
        // need to be performed to match ONNX's behavior.

        val dimMap    = (3 to 0 by -1).map(shape.takeRight(_).fold(1)(_ * _))
        val outShape  = Seq(shape(0), shape(2), shape(3), shape(1))
        val dimMapOut = (3 to 0 by -1).map(outShape.takeRight(_).fold(1)(_ * _))

        for (
          n <- 0 until shape(0);
          c <- 0 until shape(1);
          h <- 0 until shape(2);
          w <- 0 until shape(3)
        ) yield {

          val from = Array(n, c, h, w).zipWithIndex
            .map { case (e, i) => dimMap(i) * e }
            .fold(0)(_ + _)

          val to = Array(n, h, w, c).zipWithIndex
            .map { case (e, i) => dimMapOut(i) * e }
            .fold(0)(_ + _)

          val (inputIndex, inputOffset)   = inputDims.vectorIndexOffsetAt(from)
          val (outputIndex, outputOffset) = outputDims.vectorIndexOffsetAt(to)
          ((outputIndex, inputIndex), (outputOffset, inputOffset))
        }
      } else
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
        val scheduler = startLayer(Seq(reshapeProto))
        val adjustedOutputTemp = context.mm.allocateTempObject(
          reshapeProto.output(0),
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
      reshapeProto.output(0),
      outputDims,
      findInterLayerOutputs(context, reshapeProto.output(0), None),
      outputNames,
      outputAddresses
    )

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        reshapeProto,
        Seq(outputVars),
        Seq(inputVars)
      )

    r
  }

  private def emitSplit(
      context: EmitContext,
      splitProto: NodeProto
  ): EmitResult = {
    val num = splitProto.output.size

    val axisAttr = getAttr(splitProto, "axis").get

    require(axisAttr.`type`.get.isInt)

    val axis = axisAttr.i.get

    if (axis != -1)
      throw new CompilerException("Only channel split is supported")

    val inputVars =
      context.mm.consumeObject(splitProto.input(0), Seq(splitProto.name.get))

    val sizes = Array.fill(num)(inputVars.dims.lastInLayout / num)

    val inputDims = inputVars.dims

    val outputRanges =
      for (i <- 0 until sizes.size)
        yield (i, sizes.take(i).sum, sizes.take(i + 1).sum)

    var pixelDims = VarsDimensions(1, arch.arraySize)
    val outputsDims = sizes
      .map(size =>
        VarsDimensions(
          inputDims.number,
          size,
          inputDims.height,
          inputDims.width
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
        val scheduler = startLayer(Seq(splitProto))

        val adjustedOutputsTemp =
          for (i <- 0 until outputsDims.size)
            yield context.mm.allocateTempObject(
              splitProto.output(0),
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
            adjustedOutputsVars((indexPair._1._1)).span(indexPair._1._2)
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
      context.mm.blendObjects(
        splitProto.output(i),
        outputsDims(i),
        findInterLayerOutputs(context, splitProto.output(i), None),
        outputsNames(i),
        outputsAddresses(i)
      )
    }

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        splitProto,
        outputsVars.toSeq,
        Seq(inputVars)
      )

    r
  }

  private def emitConcat(
      context: EmitContext,
      concatProto: NodeProto
  ): EmitResult = {
    val num = concatProto.input.size

    val axisAttr = getAttr(concatProto, "axis").get

    require(axisAttr.`type`.get.isInt)

    val axis = axisAttr.i.get

    if (concatProto.input.forall(context.mm.hasPendingFloatConst(_))) {
      val output = concatProto.input
        .map(name => context.mm.getPendingFloatConst(name).as1D)
        .flatten

      context.mm.addPendingConst(
        concatProto.output(0),
        new TensorData(
          Shape(output.size),
          output,
          org.tensorflow.framework.types.DataType.DT_FLOAT
        )
      )

      None

    } else {

      if (axis != 1)
        throw new CompilerException("Only channel concat is supported")

      val inputsVars =
        for (i <- 0 until num)
          yield context.mm.consumeObject(
            concatProto.input(i),
            Seq(concatProto.name.get)
          )

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
        sizes.sum,
        inputsVars(0).dims.height,
        inputsVars(0).dims.width
      )

      val outputAddresses =
        Array.fill[MemoryAddress](outputDims.sizeVectors)(
          MemoryAddress.Invalid
        )

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
          val scheduler = startLayer(Seq(concatProto))

          val adjustedOutputTemp = context.mm.allocateTempObject(
            concatProto.name.get,
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
            outputAddresses(indexPair._1) =
              adjustedOutputVars.span(indexPair._1)
          }

          (
            Seq(adjustedOutputVars.name),
            finishLayer(scheduler, context)
          )
        } else
          (Nil, None)

      val outputVars = context.mm.blendObjects(
        concatProto.output(0),
        outputDims,
        findInterLayerOutputs(context, concatProto.output(0), None),
        inputsVars.map(_.name).toSeq ++ inputNamesToAdd,
        outputAddresses
      )

      if (graphPrinter.isDefined)
        graphPrinter.get.printOp(
          concatProto,
          Seq(outputVars),
          inputsVars.toSeq
        )

      r
    }
  }

  private def emitResize(
      context: EmitContext,
      resizeProto: NodeProto
  ): EmitResult = {
    val scheduler = startLayer(Seq(resizeProto))

    val inputVars =
      context.mm.consumeObject(
        resizeProto.input(0),
        Seq(resizeProto.name.get)
      )

    val inputTemp = context.mm.allocateTempObject(
      inputVars.name,
      inputVars.dims
    )

    scheduler.emitLoad(inputVars, inputTemp)

    val outputTemp = emitLayerResize(context, scheduler, resizeProto, inputTemp)

    val outputVars = context.mm.allocateVarsObject(
      outputTemp.name,
      outputTemp.dims,
      findInterLayerOutputs(context, resizeProto.output(0), None)
    )

    scheduler.emitSave(outputTemp, outputVars)

    finishLayer(scheduler, context)
  }

  private def emitLayerResize(
      context: EmitContext,
      scheduler: Scheduler,
      resizeProto: NodeProto,
      inputTemp: MemoryObject
  ): MemoryObject = {
    val modeAttr = getAttr(resizeProto, "mode").get

    require(modeAttr.`type`.get.isString)

    val mode = modeAttr.s.get

    if (mode.toStringUtf8 != "linear")
      throw new CompilerException(
        s"Resize with ${mode} mode is not supported"
      )

    val scales =
      if (context.mm.hasPendingFloatConst(resizeProto.input(1)))
        context.mm.getPendingFloatConst(resizeProto.input(1))
      else
        getTensorData(tensorProtos(resizeProto.input(1)))
          .asInstanceOf[TensorData[Float]]

    val (scaleWidth, scaleHeight) =
      if (scales.data.size == 4) (scales.data(2), scales.data(3))
      else
        throw new CompilerException(
          s"Unsupported resize scales ${scales.data}"
        )

    val halfPixelCenters = true
    val alignCorners     = false

    if (alignCorners)
      throw new CompilerException(
        s"Resize image with align corners is not supported"
      )

    val outputTemp = context.mm.allocateTempObject(
      resizeProto.output(0),
      VarsDimensions(
        inputTemp.dims.number,
        inputTemp.dims.channels,
        (inputTemp.dims.height * scaleHeight).toInt,
        (inputTemp.dims.width * scaleWidth).toInt
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
        s"${resizeProto.name.get}+Lerp${lerp.toString().replaceAll("[\\.-]", "_")}"

      lerpTemps.getOrElseUpdate(
        lerpName, {
          context.mm.addPendingConst(
            lerpName,
            new TensorData(
              Shape(arch.arraySize * inputTemp.dims.channelsVectors),
              Array.fill(arch.arraySize * inputTemp.dims.channelsVectors)(
                lerp.toFloat
              ),
              org.tensorflow.framework.types.DataType.DT_FLOAT
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
        resizeProto,
        Seq(outputTemp),
        Seq(inputTemp)
      )

    outputTemp
  }

  private def emitPool(
      context: EmitContext,
      poolProto: NodeProto
  ): EmitResult = {
    val scheduler = startLayer(Seq(poolProto))

    val inputVars =
      context.mm.consumeObject(poolProto.input(0), Seq(poolProto.name.get))

    val inputTemp = context.mm.allocateTempObject(
      inputVars.name,
      inputVars.dims
    )

    scheduler.emitLoad(inputVars, inputTemp)

    val outputTemp =
      emitLayerPool(context, scheduler, poolProto, inputTemp)

    val outputVars = context.mm.allocateVarsObject(
      outputTemp.name,
      outputTemp.dims,
      findInterLayerOutputs(context, poolProto.output(0), None)
    )

    scheduler.emitSave(outputTemp, outputVars)

    finishLayer(scheduler, context)
  }

  private def emitGlobalPool(
      context: EmitContext,
      globalPoolProto: NodeProto
  ): EmitResult = {
    val scheduler = startLayer(Seq(globalPoolProto))

    val inputVars =
      context.mm.consumeObject(
        globalPoolProto.input(0),
        Seq(globalPoolProto.name.get)
      )

    val inputTemp = context.mm.allocateTempObject(
      inputVars.name,
      inputVars.dims
    )

    scheduler.emitLoad(inputVars, inputTemp)

    val outputTemp =
      emitLayerGlobalPool(context, scheduler, globalPoolProto, inputTemp)

    val outputVars = context.mm.allocateVarsObject(
      outputTemp.name,
      outputTemp.dims,
      findInterLayerOutputs(context, globalPoolProto.output(0), None)
    )

    scheduler.emitSave(outputTemp, outputVars)

    finishLayer(scheduler, context)
  }

  private def emitNorm(
      context: EmitContext,
      normProto: NodeProto
  ): EmitResult = {
    val scheduler = startLayer(Seq(normProto))

    val inputVars =
      context.mm.consumeObject(normProto.input(0), Seq(normProto.name.get))

    val inputTemp = context.mm.allocateTempObject(
      inputVars.name,
      inputVars.dims
    )

    scheduler.emitLoad(inputVars, inputTemp)

    val outputTemp =
      emitLayerNorm(context, scheduler, normProto, inputTemp)

    val outputVars = context.mm.allocateVarsObject(
      outputTemp.name,
      outputTemp.dims,
      findInterLayerOutputs(context, normProto.output(0), None)
    )

    scheduler.emitSave(outputTemp, outputVars)

    finishLayer(scheduler, context)
  }

  private def emitActivate(
      context: EmitContext,
      activateProto: NodeProto
  ): EmitResult = {
    val scheduler = startLayer(Seq(activateProto))

    val inputVars =
      context.mm.consumeObject(
        activateProto.input(0),
        Seq(activateProto.name.get)
      )

    val inputTemp = context.mm.allocateTempObject(
      inputVars.name,
      inputVars.dims
    )

    scheduler.emitLoad(inputVars, inputTemp)

    val outputTemp =
      emitLayerActivate(context, scheduler, activateProto, inputTemp)

    val outputVars = context.mm.allocateVarsObject(
      outputTemp.name,
      outputTemp.dims,
      findInterLayerOutputs(context, activateProto.output(0), None)
    )

    scheduler.emitSave(outputTemp, outputVars)

    finishLayer(scheduler, context)
  }

  private def emitAdd(context: EmitContext, addProto: NodeProto): EmitResult = {
    val scheduler = startLayer(Seq(addProto))

    val input0Vars =
      context.mm.consumeObject(addProto.input(0), Seq(addProto.name.get))

    val input0Temp = context.mm.allocateTempObject(
      input0Vars.name,
      input0Vars.dims
    )

    scheduler.emitLoad(input0Vars, input0Temp)

    val outputTemp =
      emitLayerAdd(context, scheduler, addProto, input0Temp)

    val outputVars = context.mm.allocateVarsObject(
      outputTemp.name,
      outputTemp.dims,
      findInterLayerOutputs(context, addProto.output(0), None)
    )

    scheduler.emitSave(outputTemp, outputVars)

    finishLayer(scheduler, context)
  }

  private def emitLayerConv(
      context: EmitContext,
      scheduler: Scheduler,
      conv2DProto: NodeProto
  ): MemoryObject = {
    val padsAttr = getAttr(conv2DProto, "pads")

    val pads = if (padsAttr.isDefined) {
      require(padsAttr.get.`type`.get.isInts)

      padsAttr.get.ints.toVector
    } else
      Vector(0L, 0L, 0L, 0L)

    val stridesAttr = getAttr(conv2DProto, "strides")

    val strides = if (stridesAttr.isDefined) {
      require(stridesAttr.get.`type`.get.isInts)

      stridesAttr.get.ints.toVector
    } else
      Vector(1L, 1L)

    val groupAttr = getAttr(conv2DProto, "group")

    val group = if (groupAttr.isDefined) {
      require(groupAttr.get.`type`.get.isInt)

      groupAttr.get.i.get.toInt
    } else
      1

    val (stridesHeight, stridesWidth) = strides.map(_.toInt) match {
      case Seq(h, w) =>
        (h, w)
      case _ =>
        throw new CompilerException(
          s"Unsupported strides [${strides.mkString(", ")}]"
        )
    }

    val (paddingTop, paddingLeft, paddingBottom, paddingRight) =
      pads.map(_.toInt) match {
        case Seq(t, l, b, r) => (t, l, b, r)
        case _ =>
          throw new CompilerException(
            s"Unsupported pads [${pads.mkString(", ")}]"
          )
      }

    context.mm.addPendingConst(
      conv2DProto.input(1),
      getTensorData(tensorProtos(conv2DProto.input(1)))
    )

    if (conv2DProto.input.isDefinedAt(2))
      context.mm.addPendingConst(
        conv2DProto.input(2),
        getTensorData(tensorProtos(conv2DProto.input(2)))
      )

    val (weights, bias) =
      context.mm.getOrEmitWeightsAndBiasObjects(
        conv2DProto.input(1),
        if (conv2DProto.input.isDefinedAt(2)) Some(conv2DProto.input(2))
        else None,
      )

    val inputVars =
      context.mm.consumeObject(conv2DProto.input(0), Seq(conv2DProto.name.get))

    val outputTemp =
      context.mm.allocateTempObject(
        conv2DProto.output(0),
        VarsDimensions(
          inputVars.dims.number,
          weights.dims.channelsOut,
          (
            ((paddingTop + inputVars.dims.height + paddingBottom) - weights.dims.height) /
              stridesHeight
          ) + 1,
          (
            ((paddingLeft + inputVars.dims.width + paddingRight) - weights.dims.width) /
              stridesWidth
          ) + 1
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
          weights.dims.channelsIn * group,
          weights.dims.channelsOut
        ),
        group
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
        conv2DProto,
        Seq(outputTemp),
        Seq(inputVars),
        Seq(("Weights", weights)) ++ (if (bias.isDefined)
                                        Seq(("Bias", bias.get))
                                      else Nil)
      )

    outputTemp
  }

  private def emitLayerGemm(
      context: EmitContext,
      scheduler: Scheduler,
      matMulProto: NodeProto
  ): MemoryObject = {
    context.mm.addPendingConst(
      matMulProto.input(1),
      getTensorData(tensorProtos(matMulProto.input(1)))
    )

    if (matMulProto.input.isDefinedAt(2))
      context.mm.addPendingConst(
        matMulProto.input(2),
        getTensorData(tensorProtos(matMulProto.input(2)))
      )

    val (weights, bias) =
      context.mm.getOrEmitWeightsAndBiasObjects(
        matMulProto.input(1),
        if (matMulProto.input.isDefinedAt(2)) Some(matMulProto.input(2))
        else None,
      )

    val inputVars =
      context.mm.consumeObject(matMulProto.input(0), Seq(matMulProto.name.get))

    val outputTemp =
      context.mm.allocateTempObject(
        matMulProto.output(0),
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
        matMulProto,
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
      matMulProto: NodeProto
  ): MemoryObject = {
    context.mm.addPendingConst(
      matMulProto.input(1),
      getTensorData(tensorProtos(matMulProto.input(1)))
    )

    val (weights, bias) =
      context.mm.getOrEmitWeightsAndBiasObjects(
        matMulProto.input(1),
        None
      )

    val inputVars =
      context.mm.consumeObject(matMulProto.input(0), Seq(matMulProto.name.get))

    val outputTemp =
      context.mm.allocateTempObject(
        matMulProto.output(0),
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
        matMulProto,
        Seq(outputTemp),
        Seq(inputVars),
        Seq(("Weights", weights))
      )

    outputTemp
  }

  private def emitLayerPool(
      context: EmitContext,
      scheduler: Scheduler,
      poolProto: NodeProto,
      inputTemp: MemoryObject
  ): MemoryObject = {
    val kernelShapeAttr = getAttr(poolProto, "kernel_shape").get
    val stridesAttr     = getAttr(poolProto, "strides").get

    require(kernelShapeAttr.`type`.get.isInts)
    require(stridesAttr.`type`.get.isInts)

    val strides = stridesAttr.ints.toVector

    val (stridesHeight, stridesWidth) = strides.map(_.toInt) match {
      case Seq(h, w) =>
        (h, w)
      case _ =>
        throw new CompilerException(
          s"Unsupported strides [${strides.mkString(", ")}]"
        )
    }

    val kernelShape = kernelShapeAttr.ints.toVector

    val (kHeight, kWidth) = kernelShape.map(_.toInt) match {
      case Seq(h, w) => (h, w)
      case _ =>
        throw new CompilerException(
          s"Unsupported kernel shape [${kernelShape.mkString(", ")}]"
        )
    }

    val (paddingTop, paddingLeft, paddingBottom, paddingRight) = (0, 0, 0, 0)

    val outputTemp = context.mm.allocateTempObject(
      poolProto.output(0),
      VarsDimensions(
        inputTemp.dims.number,
        inputTemp.dims.channels,
        (
          ((paddingTop + inputTemp.dims.height + paddingBottom) - kHeight) /
            stridesHeight
        ) + 1,
        (
          ((paddingLeft + inputTemp.dims.width + paddingRight) - kWidth) /
            stridesWidth
        ) + 1
      )
    )

    val multiplierTemp = if (poolProto.opType.get == "AveragePool") {
      val multiplierName = s"${poolProto.name.get}+Multiplier"

      context.mm.addPendingConst(
        multiplierName,
        new TensorData(
          Shape(arch.arraySize),
          Array.fill(arch.arraySize)(
            1f / (stridesHeight * stridesWidth).toFloat
          ),
          org.tensorflow.framework.types.DataType.DT_FLOAT
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
        poolProto.opType.get match {
          case "MaxPool"     => "MaxPool"
          case "AveragePool" => "AvgPool"
        },
        pixelInputsTemp,
        pixelOutputTemp,
        multiplierTemp
      )
    }

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        poolProto,
        Seq(outputTemp),
        Seq(inputTemp)
      )

    outputTemp
  }

  private def emitLayerGlobalPool(
      context: EmitContext,
      scheduler: Scheduler,
      globalPoolProto: NodeProto,
      inputTemp: MemoryObject,
  ): MemoryObject = {
    val inputDims = inputTemp.dims
    val outputDims =
      VarsDimensions(inputDims.number, inputDims.channels, 1, 1)

    val outputTemp = context.mm.allocateTempObject(
      globalPoolProto.output(0),
      outputDims
    )

    val multiplierName = s"${globalPoolProto.name.get}+Multiplier"

    context.mm.addPendingConst(
      multiplierName,
      new TensorData(
        Shape(arch.arraySize),
        Array.fill(arch.arraySize)(
          1f / (inputDims.height * inputDims.width).toFloat
        ),
        org.tensorflow.framework.types.DataType.DT_FLOAT
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
        globalPoolProto,
        Seq(outputTemp),
        Seq(inputTemp)
      )

    outputTemp
  }

  private def emitLayerActivate(
      context: EmitContext,
      scheduler: Scheduler,
      activateProto: NodeProto,
      inputTemp: MemoryObject,
  ): MemoryObject = {
    val outputTemp = context.mm.allocateTempObject(
      activateProto.output(0),
      inputTemp.dims
    )

    activateProto.opType.get match {
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
        val alphaAttr = getAttr(activateProto, "alpha").get

        require(alphaAttr.`type`.get.isFloat)

        val alpha     = alphaAttr.f.get
        val alphaName = s"${activateProto.name.get}+Alpha"

        context.mm.addPendingConst(
          alphaName,
          new TensorData(
            Shape(arch.arraySize),
            Array.fill(arch.arraySize)(alpha),
            org.tensorflow.framework.types.DataType.DT_FLOAT
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
        activateProto,
        Seq(outputTemp),
        Seq(inputTemp)
      )

    outputTemp
  }

  private def emitLayerNorm(
      context: EmitContext,
      scheduler: Scheduler,
      normProto: NodeProto,
      inputTemp: MemoryObject,
  ): MemoryObject = {
    val epsilonAttr = getAttr(normProto, "epsilon").get

    require(epsilonAttr.`type`.get.isFloat)

    val epsilon = epsilonAttr.f.get

    val scale = getTensorData(tensorProtos(normProto.input(1)))
      .asInstanceOf[TensorData[Float]]
    val offset = getTensorData(tensorProtos(normProto.input(2)))
      .asInstanceOf[TensorData[Float]]
    val mean = getTensorData(tensorProtos(normProto.input(3)))
      .asInstanceOf[TensorData[Float]]
    val variance = getTensorData(tensorProtos(normProto.input(4)))
      .asInstanceOf[TensorData[Float]]

    val size = scale.shape(0)

    val inferenceScale  = Array.fill(size)(0f)
    val inferenceOffset = Array.fill(size)(0f)

    for (i <- 0 until size) {
      inferenceScale(i) =
        ((1 / Math.sqrt(variance.data(i) + epsilon)) * scale.data(i)).toFloat
      inferenceOffset(i) =
        (offset.data(i) - mean.data(i) * inferenceScale(i)).toFloat
    }

    val scaleName  = s"${normProto.name}+Scale"
    val offsetName = s"${normProto.name}+Offset"

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
      normProto.output(0),
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
        normProto,
        Seq(outputTemp),
        Seq(inputTemp),
        Seq(("Scale", scaleTemp), ("Offset", offsetTemp))
      )

    outputTemp
  }

  private def emitLayerAdd(
      context: EmitContext,
      scheduler: Scheduler,
      addProto: NodeProto,
      input0Temp: MemoryObject,
  ): MemoryObject = {
    val outputTemp = context.mm.allocateTempObject(
      addProto.output(0),
      input0Temp.dims
    )

    val input1Name =
      if (addProto.input(0) == input0Temp.name) addProto.input(1)
      else addProto.input(0)
    val input1Vars =
      context.mm.consumeObject(input1Name, Seq(addProto.name.get))

    scheduler.emitAdd(
      input0Temp,
      input1Vars,
      outputTemp
    )

    if (graphPrinter.isDefined)
      graphPrinter.get.printOp(
        addProto,
        Seq(outputTemp),
        Seq(input1Vars, input0Temp)
      )

    outputTemp
  }

  private def mkSub(
      obj: MemoryObject,
      offset: Int,
      dims: MemoryDimensions = VarsDimensions(arch.arraySize),
      repeats: Int = 1
  ): MemoryObject =
    obj.mkSub(
      obj.name,
      offset,
      dims,
      repeats
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
          org.tensorflow.framework.types.DataType.DT_FLOAT
        )
      )
    }

    val (weightsConst, _) =
      context.mm
        .getOrEmitWeightsAndBiasObjects(weightsName, None)

    weightsConst
  }

  private def getAttr(nodeProto: NodeProto, name: String) =
    nodeProto.attribute.find(_.name.get == name)

  private def getTensorData(tensorProto: TensorProto): TensorData[Any] = {
    val dims = tensorProto.dims

    tensorProto.dataType.get match {
      case TensorProto.DataType.INT64.value =>
        val data = if (tensorProto.rawData.isDefined) {
          val buffer = CodedInputStream.newInstance(
            tensorProto.rawData.get.asReadOnlyByteBuffer()
          )
          val a = new mutable.ArrayBuffer[Long]
          while (!buffer.isAtEnd) {
            a += buffer.readRawLittleEndian64()
          }
          a.toArray
        } else {
          tensorProto.int64Data.toArray
        }

        val shape = Shape(dims.map(_.toInt).toArray)

        new TensorData(
          shape,
          data,
          org.tensorflow.framework.types.DataType.DT_INT64
        )

      case TensorProto.DataType.FLOAT.value =>
        val data = if (tensorProto.rawData.isDefined) {
          val buffer = CodedInputStream.newInstance(
            tensorProto.rawData.get.asReadOnlyByteBuffer()
          )
          val a = new mutable.ArrayBuffer[Float]
          while (!buffer.isAtEnd) {
            a += buffer.readFloat()
          }
          a.toArray
        } else {
          tensorProto.floatData.toArray
        }

        val shape = Shape(dims.map(_.toInt).toArray)

        new TensorData(
          shape,
          data,
          org.tensorflow.framework.types.DataType.DT_FLOAT
        )
      case _ =>
        throw new NotImplementedError()
    }
  }
}
