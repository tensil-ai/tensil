/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

import tensil.mem.MemoryImplementation
import tensil.axi.Config

/**
  * @param localMemImpl which kind of memory to implement local, used for swapping out black boxes in simulation
  * @param accumulatorMemImpl which kind of memory to implement accumulators, used for swapping out black boxes in simulation
  * @param dramAxiConfig the AXI port width configurations to use for all AXI interfaces
  */
case class PlatformConfig(
    localMemImpl: MemoryImplementation.Kind,
    accumulatorMemImpl: MemoryImplementation.Kind,
    dramAxiConfig: Config,
)

object PlatformConfig {
  implicit val default = PlatformConfig(
    localMemImpl = MemoryImplementation.RegisterBank,
    accumulatorMemImpl = MemoryImplementation.RegisterBank,
    dramAxiConfig = Config.Xilinx,
  )
}
