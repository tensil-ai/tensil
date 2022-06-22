package tensil.util.decoupled

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.log2Ceil
import tensil.util
import chisel3.util.Cat

class WidthConverter(inWidth: Int, outWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(UInt(inWidth.W)))
    val out = Decoupled(UInt(outWidth.W))
  })

  if (outWidth == inWidth) {
    io.out <> io.in
  } else {
    val gcd             = util.greatestCommonDivisor(inWidth, outWidth)
    val lcm             = util.leastCommonMultiple(inWidth, outWidth)
    val numBlocks       = lcm / gcd
    val blocksPerInput  = inWidth / gcd
    val blocksPerOutput = outWidth / gcd

    val arr        = RegInit(VecInit(Array.fill(numBlocks)(0.U(gcd.W))))
    val enqPtr     = RegInit(0.U(log2Ceil(numBlocks).W))
    val deqPtr     = RegInit(0.U(log2Ceil(numBlocks).W))
    val maybeFull  = RegInit(false.B)
    val doEnq      = WireDefault(io.in.fire)
    val doDeq      = WireDefault(io.out.fire)
    val ptrMatch   = enqPtr === deqPtr
    val enqPtrNext = enqPtr +& blocksPerInput.U
    val deqPtrNext = deqPtr +& blocksPerOutput.U
    val full =
      chisel3.Mux(
        ptrMatch,
        maybeFull,
        chisel3.Mux(
          enqPtr < deqPtr,
          enqPtrNext > deqPtr,
          chisel3.Mux(
            enqPtrNext > numBlocks.U,
            (enqPtrNext % numBlocks.U) > deqPtr,
            false.B
          )
        )
      )
    val empty = chisel3.Mux(
      ptrMatch,
      !maybeFull,
      chisel3.Mux(
        deqPtr < enqPtr,
        deqPtrNext > enqPtr,
        chisel3.Mux(
          deqPtrNext > numBlocks.U,
          (deqPtrNext % numBlocks.U) > enqPtr,
          false.B
        )
      )
    )

    when(doEnq =/= doDeq) {
      maybeFull := doEnq
    }

    when(doEnq) {
      for (i <- 0 until blocksPerInput) {
        arr(enqPtr + (blocksPerInput - (i + 1)).U) := io.in.bits(
          (i + 1) * gcd - 1,
          i * gcd
        )
      }
      count(enqPtr, blocksPerInput, numBlocks)
    }

    when(doDeq) {
      count(deqPtr, blocksPerOutput, numBlocks)
    }

    io.in.ready := !full
    io.out.valid := !empty

    io.out.bits := Cat(
      for (i <- 0 until blocksPerOutput)
        yield arr(deqPtr + i.U)
    )

    // TODO handle pipe and flow for max throughput
    // if (inWidth > outWidth) {
    //   // flow
    //   when(io.in.valid) { io.out.valid := true.B }
    //   val canFlow = enqPtrNext > deqPtrNext
    //   when(empty) {
    //     // TODO transmit bits: need to select the correct blocks from arr and io.in.bits
    //     doDeq := false.B
    //     when(io.out.ready) { doEnq := false.B }
    //   }
    // } else {
    //   // inWidth < outWidth
    //   // pipe
    //   when(io.out.ready) { io.in.ready := true.B }
    // }
  }

  def count(reg: UInt, step: Int, max: Int): Unit = {
    reg := (reg + step.U) % max.U
  }
}
