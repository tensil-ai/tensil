/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

import chisel3.Bool

trait Write {
  val write: Bool
}
