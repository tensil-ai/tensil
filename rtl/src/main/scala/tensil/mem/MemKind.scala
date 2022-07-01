/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

object MemKind extends Enumeration {
  type Type = Value
  val RegisterBank, ChiselSyncReadMem, BlockRAM, XilinxURAMMacro,
      XilinxBRAMMacro = Value
}
