/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler.scheduler

import scala.collection.mutable

import tensil.tools.compiler.{MemoryAddress, MemoryTag, MemoryAddressHelper}

sealed abstract class Node extends Serializable {
  lazy val inputTemps: Seq[MemoryAddress]             = Nil
  lazy val inputVars: Seq[MemoryAddress]              = Nil
  lazy val inputNonReusableConsts: Seq[MemoryAddress] = Nil
  lazy val inputReusableConsts: Seq[MemoryAddress]    = Nil
}

sealed abstract class VarOutputNode(
    val output: MemoryAddress
) extends Node {
  require(output.tag == MemoryTag.DRAM0 || output.tag == MemoryTag.Local)
}

class SaveNode(
    val input: MemoryAddress,
    output: MemoryAddress
) extends VarOutputNode(output) {
  require(input.tag != output.tag)
  require(input.tag == MemoryTag.Temp || input.tag == MemoryTag.Local)

  override lazy val inputTemps = Seq(input)
}

sealed abstract class TempOutputNode(
    val output: MemoryAddress
) extends Node {
  require(output.tag == MemoryTag.Temp)
}

case class MatMulInput(
    val weights: Vector[MemoryAddress],
    val input: MemoryAddress
) {
  require(
    weights.forall(a =>
      a.tag == MemoryTag.DRAM1 ||
        a.tag == MemoryTag.Local ||
        a.tag == MemoryTag.Zeroes
    )
  )

  require(
    input.tag == MemoryTag.Zeroes ||
      input.tag == MemoryTag.DRAM1 ||
      input.tag == MemoryTag.DRAM0 ||
      input.tag == MemoryTag.Local
  )
}

class MatMulNode(
    val inputs: Vector[MatMulInput],
    output: MemoryAddress
) extends TempOutputNode(output) {
  require(output.tag == MemoryTag.Temp)

  override lazy val inputVars =
    inputs.map(_.input).filter(_.tag == MemoryTag.DRAM0)
  override lazy val inputNonReusableConsts =
    inputs.map(_.input).filter(_.tag == MemoryTag.DRAM1)
  override lazy val inputReusableConsts = inputs
    .flatMap(_.weights)
    .filter(_.tag == MemoryTag.DRAM1)
}

class LoadNode(
    val input: MemoryAddress,
    output: MemoryAddress
) extends TempOutputNode(output) {
  require(
    input.tag == MemoryTag.DRAM0 ||
      input.tag == MemoryTag.DRAM1 ||
      input.tag == MemoryTag.Local
  )

  override lazy val inputVars: Seq[MemoryAddress] =
    if (input.tag == MemoryTag.DRAM0) Seq(input) else Nil
  override lazy val inputNonReusableConsts: Seq[MemoryAddress] =
    if (input.tag == MemoryTag.DRAM1) Seq(input) else Nil
}

class AddNode(
    val input0: MemoryAddress,
    val input1: MemoryAddress,
    output: MemoryAddress
) extends TempOutputNode(output) {
  require(input0.tag == MemoryTag.Temp)
  require(
    input1.tag == MemoryTag.DRAM0 ||
      input1.tag == MemoryTag.DRAM1 ||
      input1.tag == MemoryTag.Local
  )

  override lazy val inputTemps = Seq(input0)
  override lazy val inputVars: Seq[MemoryAddress] =
    if (input1.tag == MemoryTag.DRAM0) Seq(input1) else Nil
  override lazy val inputNonReusableConsts: Seq[MemoryAddress] =
    if (input1.tag == MemoryTag.DRAM1) Seq(input1) else Nil
}

class BinarySIMDNode(
    val op: Int,
    val input0: MemoryAddress,
    val input1: MemoryAddress,
    output: MemoryAddress
) extends TempOutputNode(output) {
  require(input0.tag == MemoryTag.Temp)
  require(input1.tag == MemoryTag.Temp)

  override lazy val inputTemps = Seq(input0, input1)
}

class ReluNode(
    val input: MemoryAddress,
    output: MemoryAddress
) extends TempOutputNode(output) {
  require(input.tag == MemoryTag.Temp)

  override lazy val inputTemps = Seq(input)
}

class SoftmaxNode(
    val input: MemoryAddress,
    output: MemoryAddress
) extends TempOutputNode(output) {
  require(input.tag == MemoryTag.Temp)

  override lazy val inputTemps = Seq(input)
}

class LeakyReluNode(
    val alpha: MemoryAddress,
    val input: MemoryAddress,
    output: MemoryAddress
) extends TempOutputNode(output) {
  require(alpha.tag == MemoryTag.Temp)
  require(input.tag == MemoryTag.Temp)

  override lazy val inputTemps = Seq(alpha, input)
}

class ClipNode(
    val min: MemoryAddress,
    val max: MemoryAddress,
    val input: MemoryAddress,
    output: MemoryAddress
) extends TempOutputNode(output) {
  require(min.tag == MemoryTag.Temp)
  require(max.tag == MemoryTag.Temp)
  require(input.tag == MemoryTag.Temp)

  override lazy val inputTemps = Seq(min, max, input)
}

class PoolNode(
    val op: String,
    output: MemoryAddress,
    val inputs: Vector[MemoryAddress],
    val multiplier: Option[MemoryAddress]
) extends TempOutputNode(output) {
  require(
    !multiplier.isDefined || multiplier.get.tag == MemoryTag.Temp
  )
  require(inputs.forall(_.tag == MemoryTag.Temp))

  override lazy val inputTemps =
    if (multiplier.isDefined) inputs.toSeq :+ multiplier.get
    else inputs.toSeq
}

class NormNode(
    val input: MemoryAddress,
    val scale: MemoryAddress,
    val offset: MemoryAddress,
    output: MemoryAddress
) extends TempOutputNode(output) {
  require(input.tag == MemoryTag.Temp)
  require(scale.tag == MemoryTag.Temp)
  require(offset.tag == MemoryTag.Temp)

  override lazy val inputTemps = Seq(input, scale, offset)
}

class InterpolateNode(
    output: MemoryAddress,
    val inputs: Vector[MemoryAddress],
    val scales: Vector[MemoryAddress]
) extends TempOutputNode(output) {
  require(inputs.forall(_.tag == MemoryTag.Temp))
  require(scales.forall(_.tag == MemoryTag.Temp))

  override lazy val inputTemps = inputs.toSeq ++ scales.toSeq
}
