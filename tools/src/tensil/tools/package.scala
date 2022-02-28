package tensil

import tensil.tools.compiler.{MemoryAddress, MemoryObject}

package object tools {
  type TracepointsMap = Map[MemoryAddress, List[MemoryObject]]
  type CompilerSourceType = String
}
