/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import tensil.Architecture
import tensil.tools.CompilerOptions

abstract class SchedulingContext(
    val options: CompilerOptions
) {
  private var nextLayerIndexVar = 0

  def nextLayerIndex = nextLayerIndexVar

  def startLayer(): Scheduler = {
    val layerIndex = nextLayerIndexVar
    nextLayerIndexVar += 1

    mkScheduler(layerIndex)
  }

  protected def mkScheduler(layerIndex: Int): Scheduler
}
