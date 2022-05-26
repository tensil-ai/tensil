/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mutex

import chisel3._

trait Comparable[T <: Data] {
  self: T =>
  def ===(other: T): Bool
}
