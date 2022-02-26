package tf2rtl

import tf2rtl.mem.MemKind.{MemKind, RegisterBank}
import tf2rtl.axi.Config

/**
  * @param memKind which kind of memory to implement, used for swapping out black boxes in simulation
  * @param axi the AXI port width configurations to use for all AXI interfaces
  */
case class PlatformConfig(
    memKind: MemKind,
    axi: Config,
)

object PlatformConfig {
  implicit val default = PlatformConfig(
    memKind = RegisterBank,
    axi = Config.Xilinx,
  )
}
