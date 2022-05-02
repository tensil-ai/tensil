package tensil.tools.compiler

class LIRBroadcast(to: Seq[LIR]) extends LIR {
  def emitNoOp(): Unit = to.foreach(_.emitNoOp())

  def emitWait(tidToWait: Int): Unit = to.foreach(_.emitWait(tidToWait))

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit =
    to.foreach(
      _.emitMatMul(
        accumulate,
        localStride,
        localAddress,
        accumulatorStride,
        accumulatorAddress,
        size
      )
    )

  def emitSIMD(
      accumulate: Boolean,
      simdOp: Int,
      simdSourceLeft: Int,
      simdSourceRight: Int,
      simdDestination: Int,
      writeAccumulatorAddress: MemoryAddress,
      readAccumulatorAddress: MemoryAddress
  ): Unit =
    to.foreach(
      _.emitSIMD(
        accumulate,
        simdOp,
        simdSourceLeft,
        simdSourceRight,
        simdDestination,
        writeAccumulatorAddress,
        readAccumulatorAddress
      )
    )

  def emitDataMove(
      toLocal: Boolean,
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      stride: Int,
      address: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit =
    to.foreach(
      _.emitDataMove(
        toLocal,
        accumulate,
        localStride,
        localAddress,
        stride,
        address,
        size
      )
    )

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit = to.foreach(_.emitLoadWeights(localStride, localAddress, size))
}
