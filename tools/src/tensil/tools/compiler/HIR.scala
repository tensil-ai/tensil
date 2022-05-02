package tensil.tools.compiler

abstract trait HIR {
  def emitMatMul(
      weightsObj: MemoryObject,
      biasObj: Option[MemoryObject],
      inputOutputPairs: Seq[MemoryOptionalInputOutputObjects]
  ): Unit

  def emitLoad(
      inputObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit

  def emitAdd(
      input0Obj: MemoryObject,
      input1Obj: MemoryObject,
      outputObj: MemoryObject
  ): Unit

  def emitRelu(
      inputObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit

  def emitSoftmax(
      inputObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit

  def emitLeakyRelu(
      inputObj: MemoryObject,
      alphaObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit

  def emitPool(
      op: String,
      inputObjs: Seq[MemoryObject],
      outputObj: MemoryObject,
      multiplierObj: Option[MemoryObject]
  ): Unit

  def emitNorm(
      inputObj: MemoryObject,
      scaleObj: MemoryObject,
      offsetObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit

  def emitInterpolate(
      inputObjs: Seq[MemoryObject],
      scaleObjs: Seq[MemoryObject],
      outputObj: MemoryObject
  ): Unit

  def emitSave(
      inputObj: MemoryObject,
      outputObj: MemoryObject
  ): Unit
}
