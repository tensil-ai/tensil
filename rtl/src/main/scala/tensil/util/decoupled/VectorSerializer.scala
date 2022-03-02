/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chisel3.util.Decoupled
import tensil.util.zero
import chisel3.util.Cat

class VectorSerializer[T <: Data, S <: Data](
    genIn: T,
    genOut: S,
    n: Int,
    numScalarsPerWord: Int,
) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(Vec(n, genIn)))
    val out = Decoupled(genOut)
  })

  if (n % numScalarsPerWord != 0) {
    throw new Exception(
      s"VectorSerializer: n = $n must be a multiple of numScalarsPerWord = " +
        s"$numScalarsPerWord"
    )
  }
  if (genOut.getWidth % numScalarsPerWord != 0) {
    throw new Exception(
      s"VectorSerializer: genOut.width = ${genOut.getWidth} must be a " +
        s"multiple of numScalarsPerWord = $numScalarsPerWord"
    )
  }
  val m                 = n / numScalarsPerWord
  val outputScalarWidth = genOut.getWidth / numScalarsPerWord

  val bits  = RegInit(VecInit(Seq.fill(n)(zero(genIn))))
  val valid = RegInit(false.B)

  val (ctr, wrap) = chisel3.util.Counter(valid && io.out.ready, m)

  // TODO optimize this multiply away by making a counter that increments by numScalarsPerWord
  val out =
    for (i <- 0 until numScalarsPerWord)
      yield Extend(
        bits(ctr * numScalarsPerWord.U + i.U).asUInt,
        UInt(outputScalarWidth.W)
      )

  io.out.valid := valid
  io.out.bits := Cat(out.reverse).asTypeOf(genOut)
  io.in.ready := !valid || wrap

  when(io.in.ready) {
    when(io.in.valid) {
      bits := io.in.bits.asTypeOf(bits)
      valid := true.B
    }.otherwise {
      valid := false.B
    }
  }
}
