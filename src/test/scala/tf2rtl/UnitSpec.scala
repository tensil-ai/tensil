package tf2rtl

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
