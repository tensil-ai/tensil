package tf2rtl.tools

import chisel3.UInt
import tf2rtl.Architecture

case class TracepointCondition(
    tag: Int,
    prefix: String,
)

case class CompilerOptions(
    arch: Architecture,
    inputBatchSize: Int = 1,
    padding: Map[(UInt, UInt), Int] = Map.empty[(UInt, UInt), Int],
    printSummary: Boolean = false,
    printLayersSummary: Boolean = false,
    printSchedulerSummary: Boolean = false,
    printProgress: Boolean = true,
    printProgramWithComments: Boolean = false,
    printProgramFileName: Option[String] = None,
    printGraphFileName: Option[String] = None,
    tracepointConditions: Seq[TracepointCondition] = Nil,
    collectBackendStats: Boolean = false
)
