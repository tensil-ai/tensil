/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.zynq.tcu

import tensil.tcu.TCUOptions
import tensil.axi
import tensil.mem.MemoryImplementation

case class AXIWrapperTCUOptions(
    inner: TCUOptions = TCUOptions(),
    dramAxiConfig: axi.Config,
    resetActiveLow: Boolean = true,
    localMemImpl: MemoryImplementation.Kind = MemoryImplementation.BlockRAM,
    accumulatorMemImpl: MemoryImplementation.Kind =
      MemoryImplementation.BlockRAM,
)
