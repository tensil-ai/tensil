/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu.simd

import chisel3._
import tensil.util.{Demux, one, zero, times, plus, minus}

class ALU[T <: Data with Num[T]](
    gen: T,
    numOps: Int,
    numRegisters: Int,
    inputRegisters: Boolean = false,
    outputRegister: Boolean = false,
) extends Module {
  val io = IO(new Bundle {
    val op    = Input(UInt(util.log2Ceil(numOps).W))
    val input = Input(gen)
    // 0 = io.input, 1 = register 0, ...
    // for unary operations sourceRight is ignored
    val sourceLeft =
      Input(
        UInt(util.log2Ceil(numRegisters + 1).W)
      )
    val sourceRight = Input(UInt(util.log2Ceil(numRegisters + 1).W))
    // 0 = io.output only, 1 = register 0, etc. Note that the result *always*
    // goes to io.output anyway, but can optionally go to a register too
    val dest =
      Input(
        UInt(util.log2Ceil(numRegisters + 1).W)
      )
    val output = Output(gen)
  })

  val op    = if (inputRegisters) RegNext(io.op) else io.op
  val input = if (inputRegisters) RegNext(io.input) else io.input
  val sourceLeftInput =
    if (inputRegisters) RegNext(io.sourceLeft) else io.sourceLeft
  val sourceRightInput =
    if (inputRegisters) RegNext(io.sourceRight) else io.sourceRight
  val destInput = if (inputRegisters) RegNext(io.dest) else io.dest

  val reg = RegInit(VecInit(Seq.fill(numRegisters)(zero(gen))))
  val sourceLeft =
    Mux(sourceLeftInput === 0.U, input, reg(sourceLeftInput - 1.U))
  val sourceRight =
    Mux(sourceRightInput === 0.U, input, reg(sourceRightInput - 1.U))

  val dest =
    Demux(
      destInput === 0.U || op === Op.NoOp.U,
      io.output,
      reg(destInput - 1.U)
    )

  val result = Wire(gen)
  val output = if (outputRegister) RegNext(result) else result
  io.output := output
  dest := result

  // default NoOp
  result := input

  when(op === Op.Zero.U) {
    result := zero(gen)
  }
  when(op === Op.Move.U) {
    result := sourceLeft
  }

  // binary
  when(op === Op.Not.U) {
    result := one(gen)
    when(isTrue(sourceLeft)) {
      result := zero(gen)
    }
  }
  when(op === Op.And.U) {
    result := zero(gen)
    when(isTrue(sourceLeft) && isTrue(sourceRight)) {
      result := one(gen)
    }
  }
  when(op === Op.Or.U) {
    result := zero(gen)
    when(isTrue(sourceLeft) || isTrue(sourceRight)) {
      result := one(gen)
    }
  }

  // arithmetic
  when(op === Op.Increment.U) {
    result := plus(gen, sourceLeft, one(gen))
  }
  when(op === Op.Decrement.U) {
    result := minus(gen, sourceLeft, one(gen))
  }
  when(op === Op.Add.U) {
    result := plus(gen, sourceLeft, sourceRight)
  }
  when(op === Op.Subtract.U) {
    result := minus(gen, sourceLeft, sourceRight)
  }
  when(op === Op.Multiply.U) {
    result := times(gen, sourceLeft, sourceRight)
  }
  when(op === Op.Abs.U) {
    result := sourceLeft.abs
  }

  // comparative
  when(op === Op.GreaterThan.U) {
    result := zero(gen)
    when(sourceLeft > sourceRight) {
      result := one(gen)
    }
  }
  when(op === Op.GreaterThanEqual.U) {
    result := zero(gen)
    when(sourceLeft >= sourceRight) {
      result := one(gen)
    }
  }
  when(op === Op.Min.U) {
    result := sourceLeft.min(sourceRight)
  }
  when(op === Op.Max.U) {
    result := sourceLeft.max(sourceRight)
  }

  def isTrue(sig: T): Bool           = !equal(sig, zero(sig.cloneType))
  def equal(left: T, right: T): Bool = left >= right && right >= left
}
