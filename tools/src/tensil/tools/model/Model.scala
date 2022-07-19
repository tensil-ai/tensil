/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.model

import upickle.default.{ReadWriter, macroRW}
import upickle.implicits.key
import tensil.Architecture

case class Model(
    @key("name") name: String,
    @key("prog") program: Program,
    @key("consts") consts: Seq[ConstsEntry],
    @key("inputs") inputs: Seq[InputOutputEntry],
    @key("outputs") outputs: Seq[InputOutputEntry],
    @key("arch") arch: Architecture,
    @key("load_consts_to_local") loadConstsToLocal: Boolean
)

object Model {
  implicit val rw: ReadWriter[Model] = macroRW
}
