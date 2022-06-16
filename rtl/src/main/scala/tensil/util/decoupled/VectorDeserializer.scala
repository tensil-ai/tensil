/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chisel3.util.Decoupled
import tensil.util.zero

class VectorDeserializer[T <: Data, S <: Data](
    genIn: T,
    genOut: S,
    n: Int,
    numScalarsPerWord: Int,
) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(genIn))
    val out = Decoupled(Vec(n, genOut))
  })

  if (n % numScalarsPerWord != 0) {
    throw new Exception(
      s"VectorSerializer: n = $n must be a multiple of numScalarsPerWord = " +
        s"$numScalarsPerWord"
    )
  }
  if (genIn.getWidth % numScalarsPerWord != 0) {
    throw new Exception(
      s"VectorSerializer: genIn.width = ${genIn.getWidth} must be a " +
        s"multiple of numScalarsPerWord = $numScalarsPerWord"
    )
  }
  val m                 = n / numScalarsPerWord
  val outputScalarWidth = genIn.getWidth / numScalarsPerWord

  def extend(x: UInt): S = {
    Extend(x.asUInt(), UInt(genOut.getWidth.W)).asTypeOf(genOut)
  }

  if (m > 1) {
    val bits  = RegInit(VecInit(Seq.fill(n)(zero(genOut))))
    val valid = RegInit(false.B)

    val (ctr, wrap) =
      chisel3.util.Counter(io.in.fire, m)

    io.out.valid := valid
    io.out.bits := bits
    // when valid is true, wait until out.ready goes true
    io.in.ready := !valid || io.out.ready

    when(io.in.fire) {
      for (i <- 0 until numScalarsPerWord) {
        bits(ctr * numScalarsPerWord.U + i.U) := extend(
          io.in.bits.asUInt()(
            (i + 1) * outputScalarWidth - 1,
            i * outputScalarWidth,
          )
        )
      }
      valid := wrap
    }
    when(io.out.fire) {
      valid := false.B
    }
  } else {
    io.out.valid := io.in.valid
    io.in.ready := io.out.ready
    for (i <- 0 until io.out.bits.length)
      io.out.bits(i) := extend(
        io.in.bits.asUInt()(
          (i + 1) * outputScalarWidth - 1,
          i * outputScalarWidth,
        )
      )
  }
}
