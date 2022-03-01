package tensil.tcu

import chisel3._
import tensil.util

class MAC[T <: Data with Num[T]](val gen: T) extends Module {
  val io = IO(new Bundle {
    val load        = Input(Bool())
    val mulInput    = Input(gen)
    val addInput    = Input(gen)
    val output      = Output(gen)
    val passthrough = Output(gen)
  })
  // TODO allow each MAC to have more than one weight stored? This can be another
  //      top level parameter for the architecture. Means less loading weights
  //      from DRAM

  val weight      = RegInit(util.zero(gen))
  val passthrough = RegInit(util.zero(gen))
  val output      = RegInit(util.zero(gen))

  io.passthrough := passthrough
  passthrough := io.mulInput

  when(io.load) {
    weight := io.addInput
    io.output := weight
  }.otherwise {
    output := util.plus(gen, util.times(gen, io.mulInput, weight), io.addInput)
    io.output := output
  }
}
