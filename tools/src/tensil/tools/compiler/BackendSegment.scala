/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io.{File, FileOutputStream}

import tensil.InstructionLayout
import tensil.tools.TracepointCondition

class BackendSegment(
    val key: BackendSegmentKey,
    layout: InstructionLayout,
    stats: Option[Stats],
    tracepointConditions: Seq[TracepointCondition],
    resolveRefToObject: (MemoryRef) => Option[MemoryObject] = (ref) => None
) {
  val file = File.createTempFile("segment_", ".tprog")

  private val fileStream = new FileOutputStream(file)

  private val tracepointCollectorLir = new lir.TracepointCollector(
    tracepointConditions,
    resolveRefToObject
  )

  private val instructionAddressInjectorLir =
    new lir.InstructionAddressInjector(tracepointCollectorLir)

  val segmentLir = new lir.Broadcast(
    Seq(
      new lir.StreamGen(layout, fileStream, closeAtEndEmit = true),
      instructionAddressInjectorLir
    ) ++ (if (stats.isDefined) Seq(new lir.StatsGen(layout.arch, stats.get))
          else
            Nil): _*
  )

  def instructionsCount = instructionAddressInjectorLir.instructionsCount
  def instructionTracepointsMaps =
    tracepointCollectorLir.instructionTracepointsMaps
}
