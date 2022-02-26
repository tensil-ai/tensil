package tf2rtl

import tf2rtl.tools.compiler.{MemoryAddress, MemoryObject}

package object tools {
  type TracepointsMap = Map[MemoryAddress, List[MemoryObject]]
  type CompilerSourceType = String
}
