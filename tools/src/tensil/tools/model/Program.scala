package tensil.tools.model

import upickle.default.{ReadWriter, macroRW}
import upickle.implicits.key

case class Program(
    @key("file_name") fileName: String
)

object Program {
  implicit val rw: ReadWriter[Program] = macroRW
}
