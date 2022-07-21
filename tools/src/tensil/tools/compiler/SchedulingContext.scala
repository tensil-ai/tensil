/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import tensil.Architecture
import tensil.tools.CompilerOptions

abstract class SchedulingContext(
    val options: CompilerOptions
) {
  def mkScheduler(layerIndex: Int): Scheduler
}
