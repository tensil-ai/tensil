/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

object MemoryImplementation extends Enumeration {
  type Kind = Value
  val RegisterBank, ChiselSyncReadMem, BlockRAM, XilinxURAMMacro,
      XilinxBRAMMacro = Value
}
