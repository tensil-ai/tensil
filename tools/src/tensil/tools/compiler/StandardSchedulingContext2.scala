/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import tensil.Architecture
import tensil.tools.CompilerOptions

class StandardSchedulingContext2(options: CompilerOptions)
    extends SchedulingContext(options) {

  override protected def mkScheduler(layerIndex: Int): Scheduler =
    new StandardScheduler2(
      layerIndex,
      this
    )
}
