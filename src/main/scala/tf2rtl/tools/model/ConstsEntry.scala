package tf2rtl.tools.model

import upickle.default.{ReadWriter, macroRW}
import upickle.implicits.key

case class ConstsEntry(
    @key("file_name") fileName: String,
    @key("base") base: Long,
    @key("size") size: Long
)

object ConstsEntry {
  implicit val rw: ReadWriter[ConstsEntry] = macroRW
}
