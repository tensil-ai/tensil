/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.formal.tcu

import chisel3._
import chisel3.experimental.{verification => v}
import tensil.tcu._
import tensil.formal._
import chisel3.util.Queue
import tensil.mem.OutQueue
import tensil.PlatformConfig
import tensil.mem.MemoryImplementation.BlockRAM

class AccumulatorWithALUArrayFormal extends Formal {
  implicit val platformConfig =
    PlatformConfig.default.copy(accumulatorMemImpl = BlockRAM)
  val arch = tensil.Architecture.mkWithDefaults(
    arraySize = 2,
    localDepth = 8,
    accumulatorDepth = 8
  )
  val m  = Module(new AccumulatorWithALUArray(SInt(2.W), arch))
  val io = IO(m.io.cloneType)
  io <> m.io

  val controlQueue = Queue(io.control, 1, flow = true, pipe = true)
  m.io.control <> controlQueue

  val wroteQueue = OutQueue(io.wrote, 10, flow = true)
  wroteQueue <> m.io.wrote

  val computedQueue = OutQueue(io.computed, 10, flow = true)
  computedQueue <> m.io.computed

  val noopedQueue = OutQueue(io.nooped, 10, flow = true)
  noopedQueue <> m.io.nooped

  // val inputNeeded =
  //   io.control.bits.instruction.sourceLeft === 0.U || io.control.bits.instruction.sourceRight === 0.U
  // v.assume(!inputNeeded || io.control.bits.read)
  // v.assume(!io.control.bits.read)
  // v.assume(io.control.bits.instruction.sourceLeft === 0.U)
  // TODO why can't I create a stall when instruction is simd (not noop) and source left is 0 and !read?
  //      this should cause the alu array to stall as it waits for input

  val controlRead = Node(
    m.io.control,
    filter = m.io.control.bits.read && m.io.control.bits.instruction.op === 0.U
  )
  val controlWrite = Node(
    m.io.control,
    filter = m.io.control.bits.write && m.io.control.bits.instruction.op === 0.U
  )
  val controlCompute =
    Node(m.io.control, filter = m.io.control.bits.instruction.op =/= 0.U)
  val controlNoop = Node(
    m.io.control,
    filter =
      m.io.control.bits.instruction.op === 0.U &&
        !m.io.control.bits.read &&
        !m.io.control.bits.write
  )
  val input    = Node(m.io.input)
  val output   = Node(m.io.output)
  val wrote    = Node(m.io.wrote)
  val computed = Node(m.io.computed)
  val nooped   = Node(m.io.nooped)

  depends(output, controlRead)
  depends(wrote, controlWrite)
  depends(wrote, input)
  depends(computed, controlCompute)
  depends(nooped, controlNoop)

  assertNoDeadlock()
  // when(numResets > 0.U) {
  //   assert(eventually(m.io.control.ready))
  // }
}

object AccumulatorWithALUArrayFormal extends App {
  tensil.util.emitToBuildDir(new AccumulatorWithALUArrayFormal)
  Symbiyosys.emitConfig("AccumulatorWithALUArrayFormal")
}
