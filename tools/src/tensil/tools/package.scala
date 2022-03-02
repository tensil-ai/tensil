/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

import tensil.tools.compiler.{MemoryAddress, MemoryObject}

package object tools {
  type TracepointsMap = Map[MemoryAddress, List[MemoryObject]]
  type CompilerSourceType = String
}
