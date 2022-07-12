/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io._
import scala.collection.mutable
import org.tensorflow.framework.types.DataType

import tensil.tools.data.{Shape, TensorData}
import tensil.tools.util
import tensil.tools.{CompilerException, TraceContext, TracepointCondition}
import tensil.{Architecture, ArchitectureDataType}

object MemoryManager {
  object ReservedConsumers {
    val Output = "~output~"
    val Consts = "~consts~"

    val All = Seq(Output, Consts)
  }
}

class MemoryManager(
    tempSpace: MemorySpace,
    tempAllocator: MemoryObjectAllocator,
    ioSpace: MemorySpace,
    varsSpace: MemorySpace,
    constsSpace: MemorySpace,
    freeableAllocator: MemoryObjectAllocator,
    constsStream: OutputStream,
    dataType: ArchitectureDataType,
    arch: Architecture,
    mkConstsDimensions: (Shape) => MemoryDimensions,
    traceContext: TraceContext,
    tracepointConditions: Seq[TracepointCondition]
) {
  private abstract class PendingConsts {
    def add(
        name: String,
        tensorData: TensorData[Any]
    ): Unit
    def get(name: String): Option[TensorData[Any]]
  }

  private class TypedPendingConsts[T]() extends PendingConsts {
    private val consts = mutable.Map[String, TensorData[T]]()

    def add(
        name: String,
        tensorData: TensorData[Any]
    ): Unit =
      consts(name) = tensorData.asInstanceOf[TensorData[T]]

    def apply(name: String) = consts(name)

    def get(name: String) =
      consts.get(name).map(_.asInstanceOf[TensorData[Any]])

    def alias(
        name: String,
        alias: String
    ): Unit = if (consts.contains(name)) consts(alias) = consts(name)
  }

  private val pendingFloatConsts = new TypedPendingConsts[Float]()
  private val pendingIntConsts   = new TypedPendingConsts[Int]()
  private val pendingLongConsts  = new TypedPendingConsts[Long]()

  private def pendingConsts(dtype: DataType): PendingConsts =
    dtype match {
      case DataType.DT_INT32 => pendingIntConsts
      case DataType.DT_INT64 => pendingLongConsts
      case DataType.DT_FLOAT => pendingFloatConsts
      case _ =>
        throw new CompilerException("Constant data type is not supported")
    }

  def addPendingConst(name: String, tensorData: TensorData[Any]) =
    pendingConsts(tensorData.dtype).add(name, tensorData)

  def getPendingConst(dtype: DataType, name: String) =
    pendingConsts(dtype).get(name).get

  def hasPendingFloatConst(name: String) =
    pendingFloatConsts.get(name).isDefined

  def getPendingIntConst(name: String)   = pendingIntConsts(name)
  def getPendingLongConst(name: String)  = pendingLongConsts(name)
  def getPendingFloatConst(name: String) = pendingFloatConsts(name)

  def aliasPendingConst(
      name: String,
      alias: String
  ): Unit = {
    pendingIntConsts.alias(name, alias)
    pendingLongConsts.alias(name, alias)
    pendingFloatConsts.alias(name, alias)
  }

  private val constsDataStream = new DataOutputStream(constsStream)

  private val inputObjectsBuffer  = mutable.ArrayBuffer.empty[MemoryObject]
  private val outputObjectsBuffer = mutable.ArrayBuffer.empty[MemoryObject]

  def inputObjects  = inputObjectsBuffer.toSeq
  def outputObjects = outputObjectsBuffer.toSeq

  def emitOutputObject(name: String): (MemoryObject, Option[MemoryObject]) = {
    val outputObj = consumeObject(name, Nil)

    val (nonFinal, finalOutputObj) =
      if (outputObj.span(0).tag == MemoryTag.Local) {
        (
          true,
          freeableAllocator.allocateObject(
            ioSpace,
            name,
            outputObj.dims,
            Seq(MemoryManager.ReservedConsumers.Output)
          )
        )
      } else
        (false, outputObj)

    outputObjectsBuffer += finalOutputObj

    (finalOutputObj, if (nonFinal) Some(outputObj) else None)
  }

  def emitInputObject(
      name: String,
      dims: MemoryDimensions,
      consumers: Seq[String]
  ): MemoryObject = {
    val inputObj = freeableAllocator.allocateObject(
      ioSpace,
      name,
      dims,
      consumers
    )

    inputObjectsBuffer += inputObj

    emitInitialTracepoints(inputObj)

    inputObj
  }

  def allocateVarsObject(
      name: String,
      dims: MemoryDimensions,
      consumers: Seq[String]
  ): MemoryObject =
    freeableAllocator.allocateObject(
      varsSpace,
      name,
      dims,
      consumers
    )

  def blendObjects(
      name: String,
      dims: MemoryDimensions,
      consumers: Seq[String],
      blendeeNames: Seq[String],
      blendedAddresses: MemorySpan
  ): MemoryObject = {
    val obj = freeableAllocator.blendObjects(
      name,
      dims,
      consumers,
      blendeeNames,
      blendedAddresses
    )

    traceContext.blendObjects(obj, blendeeNames)

    obj
  }

  def hasObject(name: String): Boolean = freeableAllocator.hasObject(name)

  def consumeObject(name: String, consumers: Seq[String]): MemoryObject =
    freeableAllocator.consumeObject(name, consumers)

  def allocateTempObject(
      name: String,
      dims: MemoryDimensions
  ): MemoryObject =
    tempAllocator.allocateObject(tempSpace, name, dims, Nil)

  def getOrEmitWeightsAndBiasObjects(
      weightsName: String,
      biasName: Option[String]
  ): (MemoryObject, Option[MemoryObject]) = {
    val biasObject =
      if (biasName.isDefined) {
        val resolvedBiasName = biasName.get

        if (freeableAllocator.hasObject(resolvedBiasName))
          Some(
            freeableAllocator.consumeObject(resolvedBiasName, Nil)
          )
        else
          Some(mkConstObject(resolvedBiasName, constsDataStream))

      } else
        None

    val resolvedWeightsName = weightsName

    val weightsObject =
      if (freeableAllocator.hasObject(resolvedWeightsName))
        freeableAllocator.consumeObject(
          resolvedWeightsName,
          Nil
        )
      else
        mkConstObject(resolvedWeightsName, constsDataStream)

    (weightsObject, biasObject)
  }

  def getOrEmitConstObject(
      name: String,
      broadcastDims: Option[MemoryDimensions] = None
  ): MemoryObject = {
    val constObject =
      if (freeableAllocator.hasObject(name))
        freeableAllocator.consumeObject(name, Nil)
      else
        mkConstObject(name, constsDataStream, broadcastDims)

    constObject
  }

  def constsUtilization =
    if (constsSizesBuffer.isEmpty) 0f
    else
      constsSizesBuffer
        .map(p => p._1.toFloat / (p._2 * arch.arraySize).toFloat)
        .sum / constsSizesBuffer.size.toFloat

  def constsScalarSize = constsSizesBuffer.map(_._1).sum
  def constsVectorSize = constsSizesBuffer.map(_._2).sum

  private var constsUtilizationBuffer = mutable.ArrayBuffer.empty[Float]
  private var constsSizesBuffer       = mutable.ArrayBuffer.empty[(Long, Long)]

  private def addConstSize(scalarSize: Long, vectorSize: Long): Unit =
    if (scalarSize != 0 && vectorSize != 0) {
      constsSizesBuffer += ((scalarSize, vectorSize))
    }

  private def mkConstObject(
      name: String,
      stream: DataOutputStream,
      broadcastDims: Option[MemoryDimensions] = None
  ): MemoryObject = {
    val tensorData = pendingFloatConsts(name)
    val tensorSize = tensorData.shape.product
    val (dims, broadcastScalar) =
      if (
        broadcastDims.isDefined &&
        broadcastDims.get.sizeScalars != tensorSize
      ) {
        if (tensorSize != 1)
          throw new CompilerException("Only scalar broadcast is supported")

        (broadcastDims.get, true)
      } else (mkConstsDimensions(tensorData.shape), false)

    dims.buildConsts((offset: Option[Int]) =>
      dataType.writeFloatConst(
        if (offset.isDefined)
          tensorData.data(if (broadcastScalar) 0 else offset.get)
        else
          0f,
        stream
      )
    )

    addConstSize(dims.sizeScalars, dims.sizeVectors)

    val constObj = freeableAllocator.allocateObject(
      constsSpace,
      name,
      dims,
      Seq(MemoryManager.ReservedConsumers.Consts)
    )

    emitInitialTracepoints(constObj)

    constObj
  }

  private def emitInitialTracepoints(obj: MemoryObject): Unit = {
    val writer =
      new TracepointsWriter(
        tracepointConditions,
        freeableAllocator.resolveRefToObject(_)
      )
    obj.span.foreach(writer.write(_))
    traceContext.emitTracepoints(-InstructionAddress.One, writer.toMap)
  }
}
