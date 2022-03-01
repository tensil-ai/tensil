package tensil.axi

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class WriteResponse(val config: Config) extends Bundle {
  val id   = UInt(config.idWidth.W)
  val resp = UInt(2.W)

  // optional
  // not present in Xilinx AXI4
  // val user = Input(UInt(userWidth.W))

  def okay(): Bool = {
    resp === 0.U
  }

  def setOptional(): Unit = {
    // user := 0.U
  }
}

object WriteResponse {
  def apply()(implicit config: Config) =
    (new WriteResponse(config)).Lit(
      _.id   -> 0.U,
      _.resp -> 0.U,
    )
}
