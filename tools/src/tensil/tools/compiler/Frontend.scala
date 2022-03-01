package tensil.tools.compiler

import tensil.tools.data.Shape

abstract class Frontend {
  def traverse(outputNames: Seq[String]): Seq[String]
  def rewrite(program: Seq[String]): Seq[Emitter]

  def mkConstsDimensions(shape: Shape): MemoryDimensions
}
