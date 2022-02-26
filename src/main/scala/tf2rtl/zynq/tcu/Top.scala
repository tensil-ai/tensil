package tf2rtl.zynq.tcu

import chisel3._
import chisel3.experimental.FixedPoint
import tf2rtl.{PlatformConfig, axi}
import tf2rtl.axi.{
  AXI4Stream,
  connectDownstreamInterface,
  connectUpstreamInterface
}
import tf2rtl.mem.MemKind
import tf2rtl.util.Environment._
import tf2rtl.{ArchitectureDataType, Architecture}

class Top()(implicit
    val environment: Environment = Synthesis
) extends RawModule {

  val gen  = FixedPoint(16.W, 8.BP)
  val arch = Architecture.tiny

  arch.writeDriverArchitecureParams()

  val axiConfig = axi.Config.Xilinx64

  println(s"\tData type:\t$gen")
  println(s"\tAXI Config:\t$axiConfig")
  println(s"\tArchitecture:\t$arch")

  val clock = IO(Input(Clock()))
  val reset = IO(Input(Bool()))

  val instruction = IO(Flipped(new AXI4Stream(64)))
  val status      = IO(new AXI4Stream(64))
  val m_axi_dram0 = IO(new axi.ExternalMaster(axiConfig))
  val m_axi_dram1 = IO(new axi.ExternalMaster(axiConfig))
  val sample      = IO(new AXI4Stream(64))

  val envReset = environment match {
    case Simulation => reset
    case Synthesis  => !reset // make reset active-low
    case _          => reset
  }
  implicit val platformConfig: PlatformConfig = environment match {
    case Simulation => PlatformConfig(MemKind.RegisterBank, axi.Config.Xilinx)
    case Synthesis  => PlatformConfig(MemKind.XilinxBlockRAM, axi.Config.Xilinx)
    case _          => PlatformConfig(MemKind.RegisterBank, axi.Config.Xilinx)
  }

  withClockAndReset(clock, envReset) {
    val tcu = Module(
      new AXIWrapperTCU(
        axiConfig,
        gen,
        arch
      )
    )

    connectDownstreamInterface(instruction, tcu.instruction, tcu.error)
    connectUpstreamInterface(tcu.status, status, tcu.error)
    m_axi_dram0.connectMaster(tcu.dram0)
    m_axi_dram1.connectMaster(tcu.dram1)
    connectUpstreamInterface(tcu.sample, sample, tcu.error)
  }
}

object Top extends App {
  tf2rtl.util.emitToBuildDir(new Top)

  println("Generated Top.sv")
  println(" DONE")
}
