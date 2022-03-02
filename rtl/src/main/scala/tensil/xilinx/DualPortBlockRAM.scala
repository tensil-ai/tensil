package tensil.xilinx

import chisel3._
import chisel3.util.HasBlackBoxInline
import tensil.util
import tensil.ArtifactsLogger

class DualPortBlockRAM(width: Int, depth: Long)
    extends BlackBox
    with HasBlackBoxInline {

  override def desiredName: String = s"bram_dp_${width}x${depth}"

  val addressWidth = util.widthOf(depth - 1).get

  val io = IO(new Bundle {
    val clka  = Input(Bool())
    val wea   = Input(Bool())
    val ena   = Input(Bool())
    val addra = Input(UInt(addressWidth.W))
    val dia   = Input(UInt(width.W))
    val doa   = Output(UInt(width.W))

    val clkb  = Input(Bool())
    val web   = Input(Bool())
    val enb   = Input(Bool())
    val addrb = Input(UInt(addressWidth.W))
    val dib   = Input(UInt(width.W))
    val dob   = Output(UInt(width.W))
  })

  // Implementation from Vivado User Guide 901 (v2019.2)
  // https://www.xilinx.com/support/documentation/sw_manuals/xilinx2019_2/ug901-vivado-synthesis.pdf
  setInline(
    s"$desiredName.v",
    s"""// Dual-Port Block RAM with Two Write Ports
       |// File: rams_tdp_rf_rf.v
       |module $desiredName (clka,clkb,ena,enb,wea,web,addra,addrb,dia,dib,doa,dob);
       |input clka,clkb,ena,enb,wea,web;
       |input [${addressWidth - 1}:0] addra,addrb;
       |input [${width - 1}:0] dia,dib;
       |output [${width - 1}:0] doa,dob;
       |reg [${width - 1}:0] ram [${depth - 1}:0];
       |reg [${width - 1}:0] doa,dob;
       |
       |always @(posedge clka)
       |begin
       |  if (ena)
       |    begin
       |      if (wea)
       |        ram[addra] <= dia;
       |      doa <= ram[addra];
       |    end
       |end
       |
       |always @(posedge clkb)
       |begin
       |  if (enb)
       |    begin
       |      if (web)
       |        ram[addrb] <= dib;
       |      dob <= ram[addrb];
       |    end
       |end
       |endmodule""".stripMargin
  )

  ArtifactsLogger.log(desiredName)
}
