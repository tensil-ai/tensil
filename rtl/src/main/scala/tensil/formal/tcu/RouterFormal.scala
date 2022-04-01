/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.formal.tcu

import chisel3._
import chisel3.experimental.{verification => v}
import tensil.tcu._
import tensil.formal._
import chisel3.util.Queue

class RouterFormal extends Formal {
  val arch = tensil.Architecture.formal
  val m    = Module(new Router(Bool(), arch))
  val io   = IO(m.io.cloneType)
  io <> m.io

  v.assume(m.io.control.bits.size === 0.U)

  val controlQueue = Queue(io.control, 1, flow = true)
  m.io.control <> controlQueue

  val control = Router.dataflows
    .map(kind =>
      kind -> Node(m.io.control, filter = m.io.control.bits.kind === kind)
    )
    .toMap

  val accInput    = Node(m.io.acc.input)
  val accOutput   = Node(m.io.acc.output)
  val arrayInput  = Node(m.io.array.input)
  val arrayOutput = Node(m.io.array.output)
  val memInputFromAcc = Node(
    m.io.mem.input,
    filter = m.io.control.bits.kind === DataFlowControl.accumulatorToMemory
  )
  val memInputFromHost = Node(
    m.io.mem.input,
    filter = m.io.control.bits.kind === DataFlowControl.dram0ToMemory
  )
  val memOutputForAcc = Node(
    m.io.mem.output,
    filter =
      m.io.control.bits.kind === DataFlowControl.memoryToAccumulator ||
        m.io.control.bits.kind === DataFlowControl.memoryToAccumulatorAccumulate
  )
  val memOutputForArray = Node(
    m.io.mem.output,
    filter = m.io.control.bits.kind === DataFlowControl._memoryToArrayToAcc
  )
  val memOutputForHost = Node(
    m.io.mem.output,
    filter = m.io.control.bits.kind === DataFlowControl.memoryToDram0
  )

  depends(accInput, control(DataFlowControl._arrayToAcc))
  depends(accInput, control(DataFlowControl._memoryToArrayToAcc))
  depends(accInput, control(DataFlowControl.memoryToAccumulator))
  depends(accInput, control(DataFlowControl.memoryToAccumulatorAccumulate))
  depends(accInput, memOutputForAcc)
  depends(accInput, arrayOutput)

  depends(arrayInput, control(DataFlowControl._memoryToArrayToAcc))
  depends(arrayInput, memOutputForArray)

  depends(memInputFromAcc, control(DataFlowControl.accumulatorToMemory))
  depends(memInputFromAcc, accOutput)
  depends(memInputFromHost, control(DataFlowControl.dram0ToMemory))

  assertNoDeadlock()
}

object RouterFormal extends App {
  tensil.util.emitToBuildDir(new RouterFormal)
  Symbiyosys.emitConfig("RouterFormal")
}
