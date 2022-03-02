/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

import org.scalatest._
import chiseltest.ChiselScalatestTester

class UnitSpec
    extends FlatSpec
    with Matchers
    with ChiselScalatestTester
    with DecoupledTester

class FunUnitSpec
    extends FunSpec
    with Matchers
    with ChiselScalatestTester
    with DecoupledTester
