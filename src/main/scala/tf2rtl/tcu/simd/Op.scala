package tf2rtl.tcu.simd

import chisel3._

object Op {
  // needs to remain in sync with the actual number of operations available
  val numOps = 16

  // opcodes
  val NoOp = 0
  val Zero = 1
  val Move = 2

  // binary
  val Not = 3
  val And = 4
  val Or  = 5

  // arithmetic
  val Increment = 6
  val Decrement = 7
  val Add       = 8
  val Subtract  = 9
  val Multiply  = 10
  val Abs       = 11

  // comparative
  val GreaterThan      = 12
  val GreaterThanEqual = 13
  val Min              = 14
  val Max              = 15

  // TODO(tom) Add modulus and division for fixed point type.

  def isUnary(op: UInt): Bool = {
    val unaryOps = Array(Move, Not, Increment, Decrement, Abs)
    unaryOps.map(op === _.U).reduce(_ && _)
  }
}
