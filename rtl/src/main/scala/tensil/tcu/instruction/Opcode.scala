package tensil.tcu.instruction

import chisel3._

object Opcode {
  val NoOp        = 0x0.U
  val MatMul      = 0x1.U
  val DataMove    = 0x2.U
  val LoadWeights = 0x3.U
  val SIMD        = 0x4.U
  // unused 0x5-0xe
  val Configure = 0xf.U

  val all = Array(NoOp, MatMul, DataMove, LoadWeights, SIMD, Configure)
}
