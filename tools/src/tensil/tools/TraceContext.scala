/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools

import tensil.tools.compiler.{MemoryObject, InstructionAddress}

object TraceContext {
  def empty =
    new TraceContext() {
      def blendObjects(obj: MemoryObject, blendeeNames: Seq[String]): Unit = {}

      def emitTracepoints(
          instructionOffset: InstructionAddress,
          tracepointsMap: TracepointsMap
      ): Unit = {}
    }
}

abstract class TraceContext {
  def blendObjects(obj: MemoryObject, blendeeNames: Seq[String]): Unit

  def emitTracepoints(
      instructionOffset: InstructionAddress,
      tracepointsMap: TracepointsMap
  ): Unit
}
