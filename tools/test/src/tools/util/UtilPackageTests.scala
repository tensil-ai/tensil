/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.util

import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import matchers.should._
import org.tensorflow.framework.attr_value.AttrValue

class UtilPackageTests extends AnyFlatSpec with Matchers {
  "getSeqFromAttrValue" should "support Longs" in {
    val attr = AttrValue.fromAscii(
      List("list {", "i: 1", "i: 1", "i: 1", "i: 1", "}").mkString("\n")
    )

    for (t <- List("", false, 0.0f)) {
      val thrown =
        the[IllegalArgumentException] thrownBy getSeqFromAttrValue(attr, t)
      val m = thrown.getMessage
      ("Not a list of " + t.getClass.getSimpleName) should equal(m)
    }

    List(getSeqFromAttrValue(attr, 0L): _*) should equal(List(1, 1, 1, 1))
  }

  "getSeqFromAttrValue" should "support Strings" in {
    val attr = AttrValue.fromAscii(
      List("list {", "s: \"A\"", "s: \"B\"", "s: \"C\"", "s: \"D\"", "}")
        .mkString("\n")
    )

    for (t <- List(0L, false, 0.0f)) {
      val thrown =
        the[IllegalArgumentException] thrownBy getSeqFromAttrValue(attr, t)
      val m = thrown.getMessage
      ("Not a list of " + t.getClass.getSimpleName) should equal(m)
    }

    List(getSeqFromAttrValue(attr, ""): _*) should equal(
      List("A", "B", "C", "D")
    )
  }

  "getSeqFromAttrValue" should "support Booleans" in {
    val attr = AttrValue.fromAscii(
      List("list {", "b: true", "b: false", "b: false", "b: true", "}")
        .mkString("\n")
    )

    for (t <- List(0L, "", 0.0f)) {
      val thrown =
        the[IllegalArgumentException] thrownBy getSeqFromAttrValue(attr, t)
      val m = thrown.getMessage
      ("Not a list of " + t.getClass.getSimpleName) should equal(m)
    }

    List(getSeqFromAttrValue(attr, false): _*) should equal(
      List(true, false, false, true)
    )
  }

  "getSeqFromAttrValue" should "support Floats" in {
    val attr = AttrValue.fromAscii(
      List("list {", "f: 0.0", "f: 1.0", "f: 2.0", "f: 3.0", "}").mkString("\n")
    )

    for (t <- List(0L, "", false)) {
      val thrown =
        the[IllegalArgumentException] thrownBy getSeqFromAttrValue(attr, t)
      val m = thrown.getMessage
      ("Not a list of " + t.getClass.getSimpleName) should equal(m)
    }

    List(getSeqFromAttrValue(attr, 0.0f): _*) should equal(
      List(0.0f, 1.0f, 2.0f, 3.0f)
    )
  }
}
