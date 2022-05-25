/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

import org.scalatest._
import chiseltest.ChiselScalatestTester
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.flatspec.AnyFlatSpec
import matchers.should._

class UnitSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselScalatestTester
    with DecoupledTester

class FunUnitSpec
    extends AnyFunSpec
    with Matchers
    with ChiselScalatestTester
    with DecoupledTester
