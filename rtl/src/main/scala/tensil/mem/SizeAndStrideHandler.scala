package tensil.mem

import chisel3._
import chisel3.util.Decoupled
import tensil.util.decoupled.{Counter, CountBy}
import tensil.util.decoupled.QueueWithReporting
import chisel3.util.Queue

class SizeAndStrideHandler[
    T <: Bundle with Address with Size with Stride with Reverse,
    S <: Bundle with Address
](
    inGen: T,
    outGen: S,
    depth: Long,
    strideDepth: Int,
    inputQueue: Boolean = true,
    debug: Boolean = false,
    name: String = "",
) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(inGen.cloneType))
    val out = Decoupled(outGen.cloneType)
  })

  // val in =
  //   if (inputQueue) QueueWithReporting(io.in, 1 << 5, name = name) else io.in
  val in     = io.in
  val stride = 1.U << in.bits.stride

  val sizeCounter    = Counter(depth)
  val addressCounter = CountBy(depth, stride)

  io.out.valid := in.valid
  for ((name, w) <- in.bits.elements) {
    if (io.out.bits.elements.contains(name)) {
      io.out.bits.elements(name) := w
    }
  }
  // when the output type also supports size, we need to set size = 0
  if (io.out.bits.elements.contains("size")) {
    io.out.bits.elements("size") := 0.U
  }
  when(in.bits.reverse) {
    io.out.bits.address := in.bits.address - addressCounter.io.value.bits
  }.otherwise {
    io.out.bits.address := in.bits.address + addressCounter.io.value.bits
  }

  val fire = in.valid && io.out.ready

  when(sizeCounter.io.value.bits === in.bits.size) {
    in.ready := io.out.ready
    sizeCounter.io.resetValue := fire
    addressCounter.io.resetValue := fire
  }.otherwise {
    in.ready := false.B
    sizeCounter.io.value.ready := fire
    addressCounter.io.value.ready := fire
  }

  if (debug) {
    when(in.valid && in.ready) {
      printf(
        p"SizeAndStrideHandler: in = (size -> ${in.bits.size}, stride -> ${in.bits.stride}), sizeCounter = ${sizeCounter.io.value}, addressCounter = ${addressCounter.io.value}\n"
      )
    }
  }
}
