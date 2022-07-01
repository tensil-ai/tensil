/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

import tensil.mem.MemKind.{Type, RegisterBank}
import tensil.axi.Config

/**
  * @param memKind which kind of memory to implement, used for swapping out black boxes in simulation
  * @param axi the AXI port width configurations to use for all AXI interfaces
  */
case class PlatformConfig(
    localMemKind: Type,
    accumulatorMemKind: Type,
    dramAxiConfig: Config,
)

object PlatformConfig {
  implicit val default = PlatformConfig(
    localMemKind = RegisterBank,
    accumulatorMemKind = RegisterBank,
    dramAxiConfig = Config.Xilinx,
  )
}
