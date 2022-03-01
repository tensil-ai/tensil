package tensil.formal.tcu

import chisel3._
import chisel3.experimental.{verification => v}
import tensil.tcu._
import tensil.formal._
import tensil.tcu.instruction.Opcode
import tensil.mem.OutQueue
import chisel3.util.Queue

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
  val dataMove = DataFlowControl.all
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
  depends(acc, dataMove(DataFlowControl.accumulatorToMemory))
  depends(acc, dataMove(DataFlowControl.memoryToAccumulator))
  depends(acc, dataMove(DataFlowControl.memoryToAccumulatorAccumulate))
  depends(acc, simd)

  depends(array, matmul)

  depends(router, matmul)
  dataMove.map({ case (kind, node) => depends(router, node) })

  depends(memPortA, matmul)
  dataMove.map({ case (kind, node) => depends(memPortA, node) })

  depends(memPortB, loadWeights)
  depends(memPortB, dataMove(DataFlowControl.dram1ToMemory))
  // depends(memPortB, dataMove(DataFlowControl.memoryToDram1))

  depends(dram0, dataMove(DataFlowControl.memoryToDram0))
  depends(dram0, dataMove(DataFlowControl.dram0ToMemory))
  depends(dram1, dataMove(DataFlowControl.dram1ToMemory))
  // depends(dram1, dataMove(DataFlowControl.memoryToDram1))

  assertNoDeadlock()
}

object DecoderFormal extends App {
  tensil.util.emitToBuildDir(new DecoderFormal)
  Symbiyosys.emitConfig("DecoderFormal")
}
