package tf2rtl.tools.model

import upickle.default.{ReadWriter, macroRW}
import upickle.implicits.key
import tf2rtl.Architecture

case class Model(
    @key("name") name: String,
    @key("prog") program: Program,
    @key("consts") consts: Seq[ConstsEntry],
    @key("inputs") inputs: Seq[InputOutputEntry],
    @key("outputs") outputs: Seq[InputOutputEntry],
    @key("arch") arch: Architecture
)

object Model {
  implicit val rw: ReadWriter[Model] = macroRW
}
