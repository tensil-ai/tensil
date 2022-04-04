/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.formal.tcu

import chisel3._
import chisel3.experimental.{verification => v}
import tensil.tcu._
import tensil.formal._
import chisel3.util.Queue

class LocalRouterFormal extends Formal {
  val arch = tensil.Architecture.formal
  val m    = Module(new LocalRouter(Bool(), arch))
  val io   = IO(m.io.cloneType)
  io <> m.io

  v.assume(m.io.control.bits.size === 0.U)

  val controlQueue = Queue(io.control, 1, flow = true)
  m.io.control <> controlQueue

  val control = LocalRouter.dataflows
    .map(kind =>
      kind -> Node(m.io.control, filter = m.io.control.bits.kind === kind)
    )
    .toMap

  val accInput         = Node(m.io.acc.input)
  val accOutput        = Node(m.io.acc.output)
  val arrayInput       = Node(m.io.array.input)
  val arrayOutput      = Node(m.io.array.output)
  val arrayWeightInput = Node(m.io.array.weightInput)
  val memInputFromAcc = Node(
    m.io.mem.input,
    filter = m.io.control.bits.kind === LocalDataFlowControl.accumulatorToMemory
  )
  val memOutputForAcc = Node(
    m.io.mem.output,
    filter =
      m.io.control.bits.kind === LocalDataFlowControl.memoryToAccumulator
  )
  val memOutputForArray = Node(
    m.io.mem.output,
    filter =
      m.io.control.bits.kind === LocalDataFlowControl._memoryToArrayToAcc ||
        m.io.control.bits.kind === LocalDataFlowControl.memoryToArrayWeight
  )

  depends(accInput, control(LocalDataFlowControl._arrayToAcc))
  depends(accInput, control(LocalDataFlowControl._memoryToArrayToAcc))
  depends(accInput, control(LocalDataFlowControl.memoryToAccumulator))
  depends(accInput, memOutputForAcc)
  depends(accInput, arrayOutput)

  depends(arrayInput, control(LocalDataFlowControl._memoryToArrayToAcc))
  depends(arrayInput, memOutputForArray)

  depends(arrayWeightInput, control(LocalDataFlowControl.memoryToArrayWeight))
  depends(arrayWeightInput, memOutputForArray)

  depends(memInputFromAcc, control(LocalDataFlowControl.accumulatorToMemory))
  depends(memInputFromAcc, accOutput)

  assertNoDeadlock()
}

object LocalRouterFormal extends App {
  tensil.util.emitToBuildDir(new LocalRouterFormal)
  Symbiyosys.emitConfig("RouterFormal")
}
