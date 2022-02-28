package tensil.tools.compiler

object Opcode {
  val NoOp        = 0x0
  val MatMul      = 0x1
  val DataMove    = 0x2
  val LoadWeights = 0x3
  val SIMD        = 0x4
  // unused 0x5-0xe
  val Configure = 0xf
}
