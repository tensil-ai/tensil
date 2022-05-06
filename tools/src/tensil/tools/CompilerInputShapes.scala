package tensil.tools

object CompilerInputShapes {
  def mkWithBatchSize(batchSize: Int): CompilerInputShapes =
    Map(
      None -> Seq(Some(batchSize))
    )
}
