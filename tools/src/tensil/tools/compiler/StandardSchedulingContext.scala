/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import tensil.Architecture
import tensil.tools.CompilerOptions

class StandardSchedulingContext(arch: Architecture, options: CompilerOptions)
    extends SchedulingContext(arch, options) {

  override protected def mkScheduler(layerIndex: Int): Scheduler =
    new StandardScheduler(
      layerIndex,
      this
    )
}
