package tensil.formal.tcu

import chisel3._
import chisel3.experimental.{verification => v}
import tensil.tcu._
import tensil.formal._
import tensil.mem.OutQueue

class SystolicArrayFormal extends Formal {
  val arch = tensil.Architecture.mkWithDefaults(
    arraySize = 2,
    localDepth = 8,
    accumulatorDepth = 8
  )
  val m  = Module(new SystolicArray(SInt(2.W), arch.arraySize, arch.arraySize))
  val io = IO(m.io.cloneType)
  io <> m.io

  val loadedQueue = OutQueue(io.loaded, 10, flow = true)
  loadedQueue <> m.io.loaded

  val ranQueue = OutQueue(io.ran, 10, flow = true)
  ranQueue <> m.io.ran

  val controlRun    = Node(m.io.control, filter = !m.io.control.bits.load)
  val controlLoad   = Node(m.io.control, filter = m.io.control.bits.load)
  val input         = Node(m.io.input)
  val weights       = Node(m.io.weight)
  val output        = Node(m.io.output)
  val loadedWeights = Node(m.io.loaded, filter = !m.io.loaded.bits.zeroes)
  val loadedZeroes  = Node(m.io.loaded, filter = m.io.loaded.bits.zeroes)
  val ranInput      = Node(m.io.ran, filter = !m.io.ran.bits.zeroes)
  val ranZeroes     = Node(m.io.ran, filter = m.io.ran.bits.zeroes)

  depends(output, controlRun)

  depends(ranInput, input)
  depends(ranInput, controlRun)
  depends(ranZeroes, controlRun)

  depends(loadedWeights, weights)
  depends(loadedWeights, controlLoad)
  depends(loadedZeroes, controlLoad)

  assertNoDeadlock()
}

object SystolicArrayFormal extends App {
  tensil.util.emitToBuildDir(new SystolicArrayFormal)
  Symbiyosys.emitConfig("SystolicArrayFormal")
}
