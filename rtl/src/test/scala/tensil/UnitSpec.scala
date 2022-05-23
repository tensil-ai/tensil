/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

import org.scalatest._
import chiseltest.ChiselScalatestTester
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.Matcher
// import org.scalatest.matchers.{BeMatcher, EqualMatcher}

class UnitSpec
    extends AnyFlatSpec
    // with BeMatcher
    // with EqualMatcher
    with Matcher
    with ChiselScalatestTester
    with DecoupledTester

class FunUnitSpec
    extends AnyFunSpec
    // with BeMatcher
    // with EqualMatcher
    with ChiselScalatestTester
    with DecoupledTester
