package tf2rtl.axi

import chisel3._
import chisel3.util.DecoupledIO

class AXI4Stream(val _width: Int) extends Bundle {
  val tdata  = Output(UInt(_width.W))
  val tvalid = Output(Bool())
  val tready = Input(Bool())
  val tlast  = Output(Bool())

  def toDecoupled: DecoupledIO[UInt] = {
    val w = Wire(new DecoupledIO(UInt(_width.W)))
    w.bits := tdata
    w.valid := tvalid
    tready := w.ready
    w
  }

  def fromDecoupled(w: DecoupledIO[UInt], last: Bool): Unit = {
    tdata := w.bits
    tvalid := w.valid
    w.ready := tready
    tlast := last
  }

  def tieOff(): Unit = {
    tready := false.B
  }

  def tieOffFlipped(): Unit = {
    tvalid := false.B
    tdata := 0.U
  }
}
