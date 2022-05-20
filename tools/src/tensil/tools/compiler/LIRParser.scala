/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import scala.collection.mutable

object LIRParser {
  def combine(parsers: LIRParser*): LIRParser =
    new LIRParser {

      override def hasNext: Boolean =
        !parsers.filter(_.hasNext).isEmpty

      override def parseNext(lir: LIR): Unit =
        parsers.filter(_.hasNext).head.parseNext(lir)
    }
}

abstract trait LIRParser {
  def parseAll(lir: LIR): Unit = {
    while (hasNext)
      parseNext(lir)

    lir.endEmit()
  }

  def hasNext: Boolean
  def parseNext(lir: LIR): Unit
}
