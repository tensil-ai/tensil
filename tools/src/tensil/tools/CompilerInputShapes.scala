package tensil.tools

object CompilerInputShapes {
  def parse(shapeStrings: String): CompilerInputShapes = {
    shapeStrings
      .split("]\\S*,")
      .map(_.trim())
      .map(shapeString => {
        val Seq(nameString, dimsString) =
          shapeString.split('[').map(_.trim()).toSeq

        val shape = dimsString
          .split(']')(0)
          .split(',')
          .map(_.trim())
          .map(dimString =>
            if (dimString.isEmpty())
              None
            else
              Some(Integer.parseInt(dimString))
          )
          .toSeq

        val name =
          if (nameString.isEmpty())
            None
          else
            Some(nameString)

        (name, shape)
      })
      .toMap
  }

  def mkWithBatchSize(batchSize: Int): CompilerInputShapes =
    Map(
      None -> Seq(Some(batchSize))
    )
}
