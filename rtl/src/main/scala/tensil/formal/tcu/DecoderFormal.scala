/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.formal.tcu

import chisel3._
import chisel3.experimental.{verification => v}
import tensil.tcu._
import tensil.formal._
import tensil.tcu.instruction.Opcode
import tensil.mem.OutQueue
import chisel3.util.Queue
import tensil.tcu.instruction.DataMoveKind

class DecoderFormal extends Formal {
  val arch = tensil.Architecture.formal
  val m    = Module(new Decoder(arch))
  val io   = IO(m.io.cloneType)
  io <> m.io

  val instructionQueue = Queue(io.instruction, 1, flow = true)
  m.io.instruction <> instructionQueue

  val skippedQueue = OutQueue(io.skipped, 10, flow = true)
  skippedQueue <> m.io.skipped

  val noopedQueue = OutQueue(io.nooped, 10, flow = true)
  noopedQueue <> m.io.nooped

  val invalid = Node(
    m.io.instruction,
    Opcode.all.map(_ =/= m.io.instruction.bits.opcode).reduce(_ && _),
    suffix = "invalid"
  )
  val noop = Node(
    m.io.instruction,
    filter = m.io.instruction.bits.opcode === Opcode.NoOp,
    suffix = "noop",
  )
  val matmul = Node(
    m.io.instruction,
    filter = m.io.instruction.bits.opcode === Opcode.MatMul,
    suffix = "matmul",
  )
  val loadWeights = Node(
    m.io.instruction,
    filter = m.io.instruction.bits.opcode === Opcode.LoadWeights,
    suffix = "loadWeights",
  )
  val dataMove = DataMoveKind.all
    .map(kind =>
      kind ->
        Node(
          m.io.instruction,
          filter =
            m.io.instruction.bits.opcode === Opcode.DataMove &&
              m.io.instruction.bits.flags === kind,
          suffix = "dataMove",
        )
    )
    .toMap
  val simd = Node(
    m.io.instruction,
    filter = m.io.instruction.bits.opcode === Opcode.SIMD,
    suffix = "simd",
  )
  val configure = Node(
    m.io.instruction,
    filter = m.io.instruction.bits.opcode === Opcode.Configure,
    suffix = "configure",
  )

  val acc      = Node(m.io.acc)
  val array    = Node(m.io.array)
  val router   = Node(m.io.dataflow)
  val memPortA = Node(m.io.memPortA)
  val memPortB = Node(m.io.memPortB)
  val dram0    = Node(m.io.dram0)
  val dram1    = Node(m.io.dram1)
  val skipped  = Node(m.io.skipped)
  val nooped   = Node(m.io.nooped)

  depends(skipped, invalid)
  depends(nooped, noop)

  depends(acc, matmul)
  depends(acc, dataMove(DataMoveKind.accumulatorToMemory))
  depends(acc, dataMove(DataMoveKind.memoryToAccumulator))
  depends(acc, dataMove(DataMoveKind.memoryToAccumulatorAccumulate))
  depends(acc, simd)

  depends(array, matmul)

  depends(router, matmul)
  dataMove.map({ case (kind, node) => depends(router, node) })

  depends(memPortA, matmul)
  depends(memPortA, loadWeights)
  depends(memPortA, dataMove(DataMoveKind.memoryToAccumulator))
  depends(memPortA, dataMove(DataMoveKind.memoryToAccumulatorAccumulate))
  depends(memPortA, dataMove(DataMoveKind.accumulatorToMemory))

  depends(memPortB, dataMove(DataMoveKind.dram0ToMemory))
  depends(memPortB, dataMove(DataMoveKind.memoryToDram0))
  depends(memPortB, dataMove(DataMoveKind.dram1ToMemory))
  depends(memPortB, dataMove(DataMoveKind.memoryToDram1))

  depends(dram0, dataMove(DataMoveKind.memoryToDram0))
  depends(dram0, dataMove(DataMoveKind.dram0ToMemory))
  depends(dram1, dataMove(DataMoveKind.memoryToDram1))
  depends(dram1, dataMove(DataMoveKind.dram1ToMemory))

  assertNoDeadlock()
}

object DecoderFormal extends App {
  tensil.util.emitToBuildDir(new DecoderFormal)
  Symbiyosys.emitConfig("DecoderFormal")
}
