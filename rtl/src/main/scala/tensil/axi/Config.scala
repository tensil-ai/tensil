package tensil.axi

case class Config(idWidth: Int, addrWidth: Int, dataWidth: Int) {
  override def toString() = s"AXI(id=$idWidth, address=$addrWidth, data=$dataWidth)"
}

object Config {
  val Xilinx   = Config(6, 32, 32)
  val Xilinx64 = Config(6, 32, 64)
  val Xilinx128 = Config(6, 32, 128)
  val Xilinx256 = Config(6, 32, 256)
}
