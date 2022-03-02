package tensil.xilinx

import chisel3._
import chisel3.util.HasBlackBoxInline
import tensil.util
import tensil.ArtifactsLogger

class BlockRAM(width: Int, depth: Long)
    extends BlackBox
    with HasBlackBoxInline {
  override def desiredName: String = s"bram_${width}x${depth}"

  val addressWidth = util.widthOf(depth - 1).get

  val io = IO(new Bundle {
    val clk  = Input(Bool())
    val we   = Input(Bool())
    val en   = Input(Bool())
    val addr = Input(UInt(addressWidth.W))
    val di   = Input(UInt(width.W))
    val dout = Output(UInt(width.W))
  })

  setInline(
    s"$desiredName.v",
    s"""// Single-Port Block RAM Write-First Mode (recommended template)
     |// File: rams_sp_wf.v
     |module $desiredName (clk, we, en, addr, di, dout);
     |input clk;
     |input we;
     |input en;
     |input [${addressWidth - 1}:0] addr;
     |input [${width - 1}:0] di;
     |output [${width - 1}:0] dout;
     |reg [${width - 1}:0] RAM [${depth - 1}:0];
     |reg [${width - 1}:0] dout;
     |
     |always @(posedge clk)
     |begin
     |  if (en)
     |  begin
     |    if (we)
     |        RAM[addr] <= di;
     |    else
     |      dout <= RAM[addr];
     |  end
     |end
     |endmodule""".stripMargin
  )

  ArtifactsLogger.log(desiredName)
}
