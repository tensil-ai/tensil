/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools

import tensil.Architecture

case class TracepointCondition(
    tag: Int,
    prefix: String,
)

object CompilerStrategy extends Enumeration {
  type Kind = Value
  val LocalIsolated, LocalVars, LocalConsts, LocalVarsAndConsts = Value
}

case class CompilerOptions(
    arch: Architecture,
    strategy: CompilerStrategy.Kind,
    inputShapes: CompilerInputShapes = CompilerInputShapes.mkWithBatchSize(1),
    printSummary: Boolean = false,
    printLayersSummary: Boolean = false,
    printSchedulerSummary: Boolean = false,
    printPartitionsSummary: Boolean = false,
    printStridesSummary: Boolean = false,
    printInstructionsSummary: Boolean = false,
    printProgress: Boolean = true,
    printProgramWithComments: Boolean = false,
    printProgramAssembly: Boolean = false,
    printGraph: Boolean = false,
    tracepointConditions: Seq[TracepointCondition] = Nil,
    targetPath: Option[String] = None
)
