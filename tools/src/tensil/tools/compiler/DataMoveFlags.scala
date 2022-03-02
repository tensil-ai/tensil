/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

object DataMoveFlags {
  val DRAM0ToLocal: Int                 = 0
  val LocalToDRAM0: Int                 = 1
  val DRAM1ToLocal: Int                 = 2
  val LocalToDRAM1: Int                 = 3
  val AccumulatorToLocal: Int           = 12
  val LocalToAccumulator: Int           = 13
  val LocalToAccumulatorAccumulate: Int = 15
}
