/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.zynq.tcu

import tensil.tcu.TCUOptions
import tensil.axi
import tensil.mem.MemKind

case class AXIWrapperTCUOptions(
    inner: TCUOptions = TCUOptions(),
    dramAxiConfig: axi.Config,
    resetActiveLow: Boolean = true,
    localMemKind: MemKind.Type = MemKind.BlockRAM,
    accumulatorMemKind: MemKind.Type = MemKind.BlockRAM,
)
