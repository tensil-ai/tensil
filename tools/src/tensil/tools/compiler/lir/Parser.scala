/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler.lir

import scala.collection.mutable

import tensil.tools.compiler.{LIR}

object Parser {
  def concat(parsers: Parser*): Parser =
    new Parser {

      override def hasNext: Boolean =
        !parsers.filter(_.hasNext).isEmpty

      override def parseNext(lir: LIR): Unit =
        parsers.filter(_.hasNext).head.parseNext(lir)
    }
}

abstract trait Parser {
  def parseAll(lir: LIR): Unit = {
    while (hasNext)
      parseNext(lir)

    lir.endEmit()
  }

  def hasNext: Boolean
  def parseNext(lir: LIR): Unit
}
