package tf2rtl.tools.compiler

import tf2rtl.data.Shape

abstract class Frontend {
  def traverse(outputNames: Seq[String]): Seq[String]
  def rewrite(program: Seq[String]): Seq[Emitter]

  def mkConstsDimensions(shape: Shape): MemoryDimensions
}
