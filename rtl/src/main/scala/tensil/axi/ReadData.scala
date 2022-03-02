/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.axi

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class ReadData(val config: Config) extends Bundle {
  val id   = UInt(config.idWidth.W)
  val data = UInt(config.dataWidth.W)
  val resp = UInt(2.W)
  val last = Bool()

  // optional
  // not present in Xilinx AXI4
  // val user = Input(UInt(userWidth.W))

  def okay(): Bool = {
    resp === 0.U
  }

  def setID(): Unit = {
    id := 0.U
  }

  def setOptional(): Unit = {
    // user := 0.U
  }
}

object ReadData {
  def apply(data: BigInt, last: Boolean)(implicit config: Config): ReadData = {
    val d = if (data < 0) {
      (BigInt(1) << config.dataWidth) + data
    } else {
      data
    }
    (new ReadData(config)).Lit(
      _.data -> d.U,
      _.last -> last.B,
      _.id   -> 0.U,
      _.resp -> 0.U,
    )
  }

  def apply(data: UInt, last: Bool)(implicit config: Config): ReadData = {
    (new ReadData(config)).Lit(
      _.data -> data,
      _.last -> last,
      _.id   -> 0.U,
      _.resp -> 0.U,
    )
  }
}
