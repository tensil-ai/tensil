/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

object SIMDOp {
  val NoOp: Int             = 0x0
  val Zero: Int             = 0x1
  val Move: Int             = 0x2
  val Not: Int              = 0x3
  val And: Int              = 0x4
  val Or: Int               = 0x5
  val Increment: Int        = 0x6
  val Decrement: Int        = 0x7
  val Add: Int              = 0x8
  val Subtract: Int         = 0x9
  val Multiply: Int         = 0xa
  val Abs: Int              = 0xb
  val GreaterThan: Int      = 0xc
  val GreaterThanEqual: Int = 0xd
  val Min: Int              = 0xe
  val Max: Int              = 0xf
}
