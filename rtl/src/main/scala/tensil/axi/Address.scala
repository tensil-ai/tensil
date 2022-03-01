package tensil.axi

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util.log2Ceil

class Address(val config: Config) extends Bundle {
  val id    = UInt(config.idWidth.W)
  val addr  = UInt(config.addrWidth.W)
  val len   = UInt(8.W)
  val size  = UInt(3.W)
  val burst = UInt(2.W)

  // optional
  val lock  = UInt(2.W)
  val cache = UInt(4.W)
  val prot  = UInt(3.W)
  val qos   = UInt(4.W)
  // not present in Xilinx AXI4
  // val region = Output(UInt(4.W))
  // val user   = Output(UInt(userWidth.W))

  def request(address: UInt, length: UInt, cache: Option[UInt] = None): Unit = {
    addr := address
    len := length
    cache match {
      case Some(c) => this.cache := c
      case None    =>
    }
  }

  def setDefault(): Unit = {
    setID()
    setSize()
    setBurst(BurstMode.Increment)
    setOptional()
  }

  def setID(): Unit = {
    id := 0.U
  }

  def setSize(): Unit = {
    size := log2Ceil(config.dataWidth / 8).U
  }

  def setBurst(burstMode: Int): Unit = {
    burst := burstMode.U
  }

  def setOptional(): Unit = {
    lock := 0.U
    cache := 0.U
    prot := 0.U
    qos := 0.U
    // region := 0.U
    // user := 0.U
  }
}

object Address {
  def apply(
      addr: Int,
      len: Int
  )(implicit config: Config): Address =
    (new Address(config)).Lit(
      _.addr  -> addr.U,
      _.len   -> len.U,
      _.id    -> 0.U,
      _.size  -> log2Ceil(config.dataWidth / 8).U,
      _.burst -> BurstMode.Increment.U,
      _.lock  -> 0.U,
      _.cache -> 0.U,
      _.prot  -> 0.U,
      _.qos   -> 0.U
    )
}
