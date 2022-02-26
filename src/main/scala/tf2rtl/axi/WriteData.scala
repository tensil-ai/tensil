package tf2rtl.axi

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import tf2rtl.util.divCeil

class WriteData(val config: Config) extends Bundle {
  val id   = UInt(config.idWidth.W)
  val data = UInt(config.dataWidth.W)
  val strb = UInt(divCeil(config.dataWidth, 8).W)
  val last = Bool()

  // optional
  // not present in Xilinx AXI4
  // val user = Output(UInt(userWidth.W))
  def request(data: UInt, last: Bool): Unit = {
    this.data := data
    this.last := last
  }

  def setDefault(): Unit = {
    setID()
    setStrobe(None)
    setOptional()
  }

  def setID(): Unit = {
    id := 0.U
  }

  def setStrobe(strobe: Option[Int] = None): Unit = {
    strobe match {
      case Some(s) => strb := s.U
      // turn on all bytes by default
      case None =>
        strb := ((BigInt(1) << divCeil(config.dataWidth, 8)) - 1).U
    }
  }

  def setOptional(): Unit = {
    // user := 0.U
  }
}

object WriteData {
  def apply(data: BigInt, last: Boolean)(implicit config: Config): WriteData =
    (new WriteData(config)).Lit(
      _.data -> data.U,
      _.last -> last.B,
      _.id   -> 0.U,
      _.strb -> ((BigInt(1) << divCeil(config.dataWidth, 8)) - 1).U
    )

  def apply(data: UInt, last: Bool)(implicit config: Config): WriteData = {
    (new WriteData(config)).Lit(
      _.data -> data,
      _.last -> last,
      _.id   -> 0.U,
      _.strb -> ((BigInt(1) << divCeil(config.dataWidth, 8)) - 1).U
    )
  }
}
