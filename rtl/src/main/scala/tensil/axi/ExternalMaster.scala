/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.axi

import chisel3._
import chisel3.util.DecoupledIO
import tensil.util.divCeil

class ExternalMaster(val config: Config) extends Bundle {
  // write address
  val awready = Input(Bool())
  val awvalid = Output(Bool())
  val awid    = Output(UInt(config.idWidth.W))
  val awaddr  = Output(UInt(config.addrWidth.W))
  val awlen   = Output(UInt(8.W))
  val awsize  = Output(UInt(3.W))
  val awburst = Output(UInt(2.W))
  val awlock  = Output(UInt(2.W))
  val awcache = Output(UInt(4.W))
  val awprot  = Output(UInt(3.W))
  val awqos   = Output(UInt(4.W))

  // write data
  val wready = Input(Bool())
  val wvalid = Output(Bool())
  val wid    = Output(UInt(config.idWidth.W))
  val wdata  = Output(UInt(config.dataWidth.W))
  val wstrb  = Output(UInt(divCeil(config.dataWidth, 8).W))
  val wlast  = Output(Bool())

  // write response
  val bready = Output(Bool())
  val bvalid = Input(Bool())
  val bid    = Input(UInt(config.idWidth.W))
  val bresp  = Input(UInt(2.W))

  // read address
  val arready = Input(Bool())
  val arvalid = Output(Bool())
  val arid    = Output(UInt(config.idWidth.W))
  val araddr  = Output(UInt(config.addrWidth.W))
  val arlen   = Output(UInt(8.W))
  val arsize  = Output(UInt(3.W))
  val arburst = Output(UInt(2.W))
  val arlock  = Output(UInt(2.W))
  val arcache = Output(UInt(4.W))
  val arprot  = Output(UInt(3.W))
  val arqos   = Output(UInt(4.W))

  // read data
  val rready = Output(Bool())
  val rvalid = Input(Bool())
  val rid    = Input(UInt(config.idWidth.W))
  val rdata  = Input(UInt(config.dataWidth.W))
  val rresp  = Input(UInt(2.W))
  val rlast  = Input(Bool())

  def connectMaster(s: Master): Unit = {
    forward(s.writeAddress, "aw")
    forward(s.writeData, "w")
    forward(s.writeResponse, "b")
    forward(s.readAddress, "ar")
    forward(s.readData, "r")
  }

  def forward(s: DecoupledIO[Bundle], prefix: String): Unit = {
    elements(s"${prefix}valid") <> s.valid
    elements(s"${prefix}ready") <> s.ready
    for ((n, d) <- s.bits.elements) {
      val wireName = s"${prefix}$n"
      elements(wireName) <> d
    }
  }
}
