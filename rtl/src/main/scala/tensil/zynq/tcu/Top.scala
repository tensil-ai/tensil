package tensil.zynq.tcu

import chisel3._
import chisel3.experimental.FixedPoint
import tensil.{PlatformConfig, axi}
import tensil.axi.{
  AXI4Stream,
  connectDownstreamInterface,
  connectUpstreamInterface
}
import tensil.mem.MemKind
import tensil.util.Environment._
import tensil.{
  ArchitectureDataType,
  Architecture,
  TablePrinter,
  InstructionLayout,
  ArtifactsLogger
}
import java.io.File

case class Args(
    archFile: File = new File("."),
    dramAxiConfig: axi.Config = axi.Config.Xilinx64,
    verbose: Boolean = false,
    summary: Boolean = false,
)

class Top(archName: String, arch: Architecture, dramAxiConfig: axi.Config)(
    implicit val environment: Environment = Synthesis
) extends RawModule {
  override def desiredName: String = s"top_${archName}"

  val gen = arch.dataType match {
    case ArchitectureDataType.FP8BP4   => FixedPoint(8.W, 4.BP)
    case ArchitectureDataType.FP16BP8  => FixedPoint(16.W, 8.BP)
    case ArchitectureDataType.FP18BP10 => FixedPoint(18.W, 10.BP)
    case ArchitectureDataType.FP32BP16 => FixedPoint(32.W, 16.BP)
    case dataType =>
      throw new Exception(s"${dataType} not supported")
  }
  val layout = new InstructionLayout(arch)

  val clock       = IO(Input(Clock()))
  val reset       = IO(Input(Bool()))
  val instruction = IO(Flipped(new AXI4Stream(layout.instructionSizeBytes * 8)))
  val status      = IO(new AXI4Stream(64))
  val m_axi_dram0 = IO(new axi.ExternalMaster(dramAxiConfig))
  val m_axi_dram1 = IO(new axi.ExternalMaster(dramAxiConfig))
  val sample      = IO(new AXI4Stream(64))

  val envReset = environment match {
    case Simulation => reset
    case Synthesis  => !reset // make reset active-low
    case _          => reset
  }
  implicit val platformConfig: PlatformConfig = environment match {
    case Simulation => PlatformConfig(MemKind.RegisterBank, dramAxiConfig)
    case Synthesis  => PlatformConfig(MemKind.XilinxBlockRAM, dramAxiConfig)
    case _          => PlatformConfig(MemKind.RegisterBank, dramAxiConfig)
  }

  withClockAndReset(clock, envReset) {
    val tcu = Module(
      new AXIWrapperTCU(
        dramAxiConfig,
        gen,
        layout
      )
    )

    connectDownstreamInterface(instruction, tcu.instruction, tcu.error)
    connectUpstreamInterface(tcu.status, status, tcu.error)
    m_axi_dram0.connectMaster(tcu.dram0)
    m_axi_dram1.connectMaster(tcu.dram1)
    connectUpstreamInterface(tcu.sample, sample, tcu.error)
  }

  ArtifactsLogger.log(desiredName)
}

object Top extends App {
  val argParser = new scopt.OptionParser[Args]("make_rtl") {
    help("help").text("Prints this usage text")
    
    opt[File]('a', "arch")
      .required()
      .valueName("<file>")
      .action((x, c) => c.copy(archFile = x))
      .text("Tensil architecture descrition (.tarch) file")

    opt[Int]('d', "dram-axi-width")
      .valueName("32|64|128|256")
      .validate(x =>
        if (Seq(32, 64, 128, 256).contains(x)) success
        else failure("Value must be 64, 128 or 256")
      )
      .action((x, c) =>
        c.copy(dramAxiConfig = x match {
          case 32  => axi.Config.Xilinx
          case 64  => axi.Config.Xilinx64
          case 128 => axi.Config.Xilinx128
          case 256 => axi.Config.Xilinx256
        })
      )
      .text("Optional DRAM0 and DRAM1 AXI width, defaults to 64")
  }

  argParser.parse(args, Args()) match {
    case Some(args) =>
      val arch     = Architecture.read(args.archFile)
      val archName = args.archFile.getName().split("\\.")(0)

      tensil.util.emitToBuildDir(new Top(archName, arch, args.dramAxiConfig))

      val archParamsFileName = "build/architecture_params.h"
      arch.writeDriverArchitectureParams(archParamsFileName)

      val tb = new TablePrinter(Some("ARTIFACTS"))

      for (artifact <- ArtifactsLogger.artifacts)
        tb.addNamedLine(
          s"Verilog $artifact",
          new File(s"build/${artifact}.v").getAbsolutePath()
        )

      tb.addNamedLine(
        "Driver parameters C header",
        new File(archParamsFileName).getAbsolutePath()
      )

      print(tb)

    case _ =>
      sys.exit(1)
  }
}
