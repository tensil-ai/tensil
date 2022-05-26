/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mutex

import chisel3._

trait ConditionalRelease[T <: Data] {
  val delayRelease: UInt
  val cond: T
}
