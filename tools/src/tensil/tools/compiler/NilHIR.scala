package tensil.tools.compiler

class NilHIR extends HIR {

  override def emitMatMul(
      weightsObj: MemoryObject,
      biasObj: Option[MemoryObject],
      inputOutputPairs: Seq[MemoryOptionalInputOutputObjects]
  ): Unit = {}

  override def emitLoad(
      inputObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {}

  override def emitAdd(
      input0Obj: MemoryObject,
      input1Obj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {}

  override def emitSub(
      input0Obj: MemoryObject,
      input1Obj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {}

  override def emitMul(
      input0Obj: MemoryObject,
      input1Obj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {}

  override def emitRelu(
      inputObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {}

  override def emitSoftmax(
      inputObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {}

  def emitClip(
      inputObj: MemoryObject,
      minObj: MemoryObject,
      maxObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {}

  override def emitLeakyRelu(
      inputObj: MemoryObject,
      alphaObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {}

  override def emitPool(
      op: String,
      inputObjs: Seq[MemoryObject],
      outputObj: MemoryObject,
      multiplierObj: Option[MemoryObject]
  ): Unit = {}

  override def emitNorm(
      inputObj: MemoryObject,
      scaleObj: MemoryObject,
      offsetObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {}

  override def emitInterpolate(
      inputObjs: Seq[MemoryObject],
      scaleObjs: Seq[MemoryObject],
      outputObj: MemoryObject
  ): Unit = {}

  override def emitSave(
      inputObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit = {}

}
