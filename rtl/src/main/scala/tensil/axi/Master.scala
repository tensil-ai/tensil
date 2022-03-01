package tensil.axi

import chisel3._
import chisel3.util.Decoupled

class Master(val config: Config) extends Bundle {
  val writeAddress  = Decoupled(new Address(config))
  val writeData     = Decoupled(new WriteData(config))
  val writeResponse = Flipped(Decoupled(new WriteResponse(config)))

  val readAddress = Decoupled(new Address(config))
  val readData    = Flipped(Decoupled(new ReadData(config)))
}
