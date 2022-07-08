/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import tensil.Architecture
import tensil.tools.CompilerOptions

class StandardSchedulingContext2(options: CompilerOptions)
    extends SchedulingContext(options) {

  val localSpace =
    HeapMemorySpace(
      "local",
      MemoryTag.Local,
      options.arch.threadLocalDepth
    )
  val localAllocator =
    RenamingMemoryAllocator(localSpace, Set(MemoryTag.Consts, MemoryTag.Vars))

  override def emitPostamble(backend: Backend): EmitResult = {
    val saveKey =
      BackendSegmentKey(nextLayerIndex, 0, 0, BackendSegmentKey.Save)
    val saveStats = new Stats()

    val saveSegment = backend.mkSegment(
      saveKey,
      Some(saveStats)
    )

    val saveLocalRollup = new DoubleAddressRollup(
      saveSegment.segmentLir
        .emitDataMove(toLocal = false, accumulate = false, _, _, _, _, _),
      options.arch
    )

    for (
      (outputVarsAddress, inputLocalAddress) <-
        localAllocator.map.toList
          .filter(_._1.tag == MemoryTag.Vars)
          .sortBy(_._1)
    )
      saveLocalRollup.emit(
        inputLocalAddress,
        outputVarsAddress
      )

    saveLocalRollup.finalEmit()
    saveSegment.segmentLir.endEmit()
    backend.emitSegment(saveSegment)

    None
  }

  override def freeConsumedObjects(freedSpan: MemorySpan): Unit = {
    localAllocator.freeTag(MemoryTag.Consts)
    localAllocator.freeSpan(freedSpan)
  }

  override protected def mkScheduler(layerIndex: Int): Scheduler =
    new StandardScheduler2(
      layerIndex,
      this
    )
}
