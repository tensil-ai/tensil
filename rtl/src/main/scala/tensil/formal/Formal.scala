/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.formal

import chisel3._
import chisel3.experimental.{verification => v}
import chisel3.experimental.DataMirror
import tensil.tcu._
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3.util.DecoupledIO
import chisel3.util.TransitName
import scala.collection.mutable.ArrayBuffer
import chisel3.util.HasBlackBoxInline

abstract class Formal extends Module {
  type Dec = DecoupledIO[Data]

  case class Node(
      port: Dec,
      filter: Bool = true.B,
      suffix: String = "",
  ) {
    val isOutput = do_isOutput(port)
    val name     = port.instanceName + "_" + suffix
    val count    = do_count(port.fire() && filter)
    count.suggestName(name + "_count")
  }

  case class Dependency(
      input: Node,
      output: Node,
  ) {
    if (!output.isOutput) {
      throw new Exception("output node must have port with direction output")
    }
    if (input.isOutput) {
      throw new Exception("input node must have port with direction input")
    }
    val name = input.name + "_to_" + output.name
  }

  val resetCounter = Module(new ResetCounter)
  resetCounter.io.clock := clock
  resetCounter.io.reset := reset
  val numResets = resetCounter.io.numResets

  def assertNoDeadlock(): Unit = {
    for (dep <- dependencies) {
      v.assume(eventually(dep.input.port.valid))
      v.assume(eventually(dep.output.port.ready))
    }

    v.cover(numResets > 0.U)
    when(numResets > 0.U && !reset.asBool()) {
      v.assert(noExtraneousDependencies(), "failed ned")
      v.assert(selfCleaning(), "failed sc")
    }
  }

  val _dependenciesBuilder = new ArrayBuffer[Dependency]
  lazy val dependencies    = _dependenciesBuilder.toArray

  def min(values: Seq[UInt]): UInt =
    values
      .reduce((l, r) => Mux(l < r, l, r))

  def noExtraneousDependencies(): Bool = {
    val outputs =
      dependencies.map(dep => dep.output).toSet
    val minOutputCount = min(dependencies.map(_.output.count))
    val minInputCount  = min(dependencies.map(_.input.count))

    val clauses = for (output <- outputs) yield {
      val deps =
        dependencies.filter(dep => dep.output == output)
      val minConnectedInputCount = min(deps.map(_.input.count))
      val obligated =
        minConnectedInputCount > minOutputCount && minInputCount >= minOutputCount && output.count === minOutputCount
      val fulfilled = eventually(
        releases(output.port.ready, output.port.valid),
        reset = !obligated
      )
      fulfilled.suggestName("ned_" + output.name)
      implies(obligated, fulfilled)
    }
    clauses.reduce(_ && _)
  }

  def selfCleaning(): Bool = {
    val inputs =
      dependencies.map(dep => dep.input).toSet
    val minOutputCount = min(dependencies.map(_.output.count))

    val clauses = for (input <- inputs) yield {
      val obligated = input.count < minOutputCount
      val fulfilled =
        eventually(
          releases(input.port.valid, input.port.ready),
          reset = !obligated
        )
      fulfilled.suggestName("sc_" + input.name)
      implies(obligated, fulfilled)
    }
    clauses.reduce(_ && _)
  }

  def do_isOutput(x: Dec): Boolean =
    DataMirror.directionOf(x.valid) == ActualDirection.Output

  def depends(
      output: Node,
      input: Node,
  ): Unit =
    _dependenciesBuilder += Dependency(
      input,
      output,
    )

  def implies(antecedent: Bool, consequent: Bool): Bool =
    Mux(antecedent, consequent, true.B)

  def weakUntil(condition: Bool, signal: Bool): Bool = {
    // condition must remain true until signal becomes true
    val reg = RegInit(false.B)
    when(signal) {
      reg := true.B
    }
    Mux(signal || reg, true.B, condition)
  }

  def eventually(
      condition: Bool,
      timeout: Int = 7,
      reset: Bool = false.B
  ): Bool = {
    // condition must become true before timeout elapses
    val counter = Counter(timeout + 1, !condition, reset || condition)
    TransitName(counter.io.wrap, counter)
    dontTouch(counter.io.value)
    !counter.io.wrap
  }

  def until(condition: Bool, signal: Bool): Bool = {
    weakUntil(condition, signal) && eventually(signal)
  }

  def releases(signal: Bool, condition: Bool): Bool = {
    weakUntil(condition, condition && signal)
  }

  def do_count(
      condition: Bool,
      maxCount: Int = 1 << 8,
  ): UInt = {
    val counter = Counter(maxCount, condition)
    TransitName(counter.io.value, counter)
    counter.io.value
  }
}

class ResetCounter extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock          = Input(Clock())
    val reset          = Input(Reset())
    val numResets      = Output(UInt(32.W))
    val timeSinceReset = Output(UInt(32.W))
  })
  setInline(
    "ResetCounter.sv",
    s"""module ResetCounter(
       |  input clock,
       |  input reset,
       |  output [31:0] numResets,
       |  output [31:0] timeSinceReset
       |);
       |  reg [31:0] num_resets;
       |  reg [31:0] t_since_reset;
       |  initial num_resets = 32'h00000000;
       |  initial t_since_reset = 32'h00000000;
       |  assign numResets = num_resets;
       |  assign timeSinceReset = t_since_reset;
       |
       |  always @(posedge clock) begin
       |    if (t_since_reset != 32'hFFFFFFFF) begin
       |      t_since_reset <= t_since_reset + 32'h00000001;
       |    end
       |    if (reset) begin
       |      if (num_resets != 32'hFFFFFFFF) begin
       |        num_resets <= num_resets + 32'h00000001;
       |      end
       |      t_since_reset <= 32'h00000000;
       |    end
       |  end
       |endmodule
       |""".stripMargin
  )
}
