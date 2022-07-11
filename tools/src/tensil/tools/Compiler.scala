/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools

import java.io._
import scala.collection.mutable
import org.tensorflow.framework.graph.GraphDef
import onnx.onnx.ModelProto
import tensil.{
  Architecture,
  ArchitectureDataType,
  TablePrinter,
  TableLine,
  InstructionLayout
}
import tensil.tools.model.{Model, Program, ConstsEntry, InputOutputEntry}
import tensil.tools.compiler.{
  Backend,
  Frontend,
  TfFrontend,
  OnnxFrontend,
  EmitContext,
  MemoryManager,
  StrideStats,
  MemoryObject,
  MemoryTag,
  MemoryAddressHelper,
  SchedulerResult,
  Stats,
  StandardSchedulingContext,
  StandardSchedulingContext2
}

class CompilerException(message: String) extends Exception(message) {}

case class CompilerStats(
    constsUsedSize: Long,
    varsUsedSize: Long,
    layersNumber: Int,
    programSizeBytes: Long,
    constsScalarSize: Long,
    constsUtilization: Float,
    cycles: Long,
    energy: Long,
    macs: Long,
    macEfficiency: Float
) {}

case class CompilerArtifact(
    kind: String,
    fileName: String
) {}

case class CompilerArtifactsAndResult(
    result: CompilerResult,
    artifacts: Seq[CompilerArtifact]
) {}

case class CompilerResult(
    arch: Architecture,
    inputObjects: Seq[MemoryObject],
    outputObjects: Seq[MemoryObject],
    stats: CompilerStats
) {}

object CompilerSourceType {
  val Tensorflow = TfFrontend.ModelFileNameExtension
  val ONNX       = OnnxFrontend.ModelFileNameExtension
}

object Compiler {
  def getModelSourceType(modelFileName: String): CompilerSourceType = {
    val i = modelFileName.lastIndexOf('.');

    if (i > 0)
      modelFileName.substring(i + 1)
    else
      ""
  }

  def compile(
      modelName: String,
      modelFileName: String,
      outputNames: Seq[String],
      options: CompilerOptions,
      traceContext: TraceContext = TraceContext.empty
  ): CompilerArtifactsAndResult = {
    val modelStream     = new FileInputStream(modelFileName)
    val modelSourceType = getModelSourceType(modelFileName)

    compileStreamToFiles(
      modelName,
      modelSourceType,
      modelStream,
      outputNames,
      options,
      traceContext
    )
  }

  def compileStreamToFiles(
      modelName: String,
      modelSourceType: CompilerSourceType,
      modelStream: InputStream,
      outputNames: Seq[String],
      options: CompilerOptions,
      traceContext: TraceContext = TraceContext.empty
  ): CompilerArtifactsAndResult = {
    val prefix =
      if (options.targetPath.isDefined && !options.targetPath.get.isEmpty())
        options.targetPath.get + (if (options.targetPath.get.endsWith("/")) ""
                                  else "/")
      else ""

    val constsFileName  = s"${modelName}.tdata"
    val programFileName = s"${modelName}.tprog"

    val constsFilePath   = s"${prefix}${constsFileName}"
    val programFilePath  = s"${prefix}${programFileName}"
    val manifestFilePath = s"${prefix}${modelName}.tmodel"
    val graphFilePath =
      if (options.printGraph) Some(s"${prefix}${modelName}.dot") else None
    val programAssemblyFilePath =
      if (options.printProgramAssembly) Some(s"${prefix}${modelName}.tasm")
      else None

    val constsStream  = new FileOutputStream(constsFilePath)
    val programStream = new FileOutputStream(programFilePath)

    val result = compileStreamToStreams(
      modelName,
      modelSourceType,
      modelStream,
      outputNames,
      programStream,
      constsStream,
      options,
      traceContext,
      programAssemblyFilePath,
      graphFilePath
    )

    constsStream.close()
    programStream.close()

    def objectToEntries(obj: MemoryObject) = {
      require(obj.span.forall(_.tag == MemoryTag.DRAM0))

      var entries = mutable.ArrayBuffer.empty[InputOutputEntry]

      var base = obj.span.head.raw
      for (i <- 1 until obj.span.size) {
        val nextExpected = obj.span(i - 1).raw + 1
        val nextBase     = obj.span(i).raw

        if (nextExpected != nextBase) {
          entries += InputOutputEntry(
            name = obj.name,
            base = base,
            size = nextExpected - base
          )
          base = nextBase
        }
      }

      entries += InputOutputEntry(
        name = obj.name,
        base = base,
        size = obj.span.last.raw + 1 - base
      )

      entries.toSeq
    }

    def objectsToEntries(objs: Seq[MemoryObject]) =
      objs
        .map(objectToEntries(_))
        .flatten
        .toArray
        .sortBy(_.base)
        .toSeq

    val model = Model(
      name = modelName,
      program = Program(
        fileName = programFileName,
        size = result.stats.programSizeBytes
      ),
      consts = Seq(
        ConstsEntry(
          fileName = constsFileName,
          base = 0,
          size = result.stats.constsUsedSize
        )
      ),
      inputs = objectsToEntries(result.inputObjects),
      outputs = objectsToEntries(result.outputObjects),
      arch = options.arch
    )

    val manifestStream = new FileOutputStream(manifestFilePath)

    upickle.default.writeToOutputStream(model, manifestStream)
    manifestStream.close()

    CompilerArtifactsAndResult(
      result = result,
      artifacts = Seq(
        CompilerArtifact("Manifest", manifestFilePath),
        CompilerArtifact("Program", programFilePath),
        CompilerArtifact("Constants", constsFilePath)
      ) ++ (if (graphFilePath.isDefined)
              Seq(
                CompilerArtifact(
                  "Graph",
                  graphFilePath.get
                )
              )
            else Nil) ++ (if (programAssemblyFilePath.isDefined)
                            Seq(
                              CompilerArtifact(
                                "Program assembly",
                                programAssemblyFilePath.get
                              )
                            )
                          else Nil)
    )
  }

  def compileStreamToStreams(
      modelName: String,
      modelSourceType: CompilerSourceType,
      modelStream: InputStream,
      outputNames: Seq[String],
      programStream: OutputStream,
      constsStream: OutputStream,
      options: CompilerOptions,
      traceContext: TraceContext = TraceContext.empty,
      programAssemblyFilePath: Option[String] = None,
      graphFilePath: Option[String] = None
  ): CompilerResult = {
    val startTime = System.nanoTime()

    val graphStream = graphFilePath.map(new FileOutputStream(_))

    val frontend: Frontend =
      if (modelSourceType == CompilerSourceType.Tensorflow) {
        new TfFrontend(
          graphDef = util.protoFromStream(GraphDef, modelStream),
          arch = options.arch,
          graphStream = graphStream,
          options = options
        )
      } else if (modelSourceType == CompilerSourceType.ONNX) {
        new OnnxFrontend(
          modelProto = util.protoFromStream(ModelProto, modelStream),
          arch = options.arch,
          graphStream = graphStream,
          options = options
        )
      } else
        throw new CompilerException(
          s"No frontend to support ${modelSourceType}"
        )

    val mm = new MemoryManager(
      constsStream = constsStream,
      dataType = options.arch.dataType,
      arch = options.arch,
      mkConstsDimensions = frontend.mkConstsDimensions,
      traceContext = traceContext,
      tracepointConditions = options.tracepointConditions
    )

    val layout =
      InstructionLayout(options.arch)
    val backend = new Backend(
      layout = layout,
      tracepointConditions = options.tracepointConditions,
      resolveRefToObject = mm.resolveRefToObject(_),
      traceContext = traceContext
    )

    var layerSchedulerResults = mutable.ArrayBuffer.empty[SchedulerResult]
    var macs                  = 0L
    var macEfficiency         = 0f
    val backendStats          = new Stats()

    try {
      if (options.printProgress)
        println(
          s"Traversing from output node(s): ${outputNames.mkString(",")} ..."
        )

      val flowNodeNames = frontend.traverse(outputNames)

      if (options.printProgress) {
        println(s"Found ${flowNodeNames.size} node(s)")
        println(s"Rewriting emitters ...")
      }

      val flowEmitters = frontend.rewrite(flowNodeNames)

      if (options.printProgress) {
        println(s"Rewritten to ${flowEmitters.size} emitter(s)")
      }

      var nextLayerIndex    = 0
      val schedulingContext = new StandardSchedulingContext(options)
      //new StandardSchedulingContext2(options, mm.localSpace)

      for (emitter <- flowEmitters) {
        if (frontend.graphPrinter.isDefined)
          frontend.graphPrinter.get.startLayer(
            s"layer_${nextLayerIndex}"
          )

        val scheduler = schedulingContext.mkScheduler(nextLayerIndex)

        emitter(
          EmitContext(
            hir = scheduler,
            mm = mm,
            outputNames = outputNames
          )
        )

        if (frontend.graphPrinter.isDefined)
          frontend.graphPrinter.get.endLayer()

        val r = scheduler.lower(backend)
        mm.freeConsumedObjects()

        if (r.numberOfStages != 0) {
          nextLayerIndex += 1
          layerSchedulerResults += r
        }
      }

      if (frontend.graphPrinter.isDefined) frontend.graphPrinter.get.endPrint

      backend.writeSegments(
        programStream,
        programAssemblyFilePath,
        Some(backendStats)
      )

      macs = layerSchedulerResults.map(_.macs).sum
      macEfficiency = Stats.macEfficiency(backendStats, options.arch, macs)

      val programSizeBytes =
        backend.instructionsCount * layout.instructionSizeBytes
      val stats =
        CompilerStats(
          constsUsedSize = mm.dram1Space.maxSize,
          varsUsedSize = mm.dram0Space.maxSize,
          layersNumber = layerSchedulerResults.size,
          programSizeBytes = programSizeBytes,
          constsScalarSize = mm.constsScalarSize,
          constsUtilization = mm.constsUtilization,
          cycles = backendStats.executionCycles,
          energy = backendStats.executionEnergy,
          macs = macs,
          macEfficiency = macEfficiency
        )

      CompilerResult(
        arch = options.arch,
        inputObjects = mm.inputObjects,
        outputObjects = mm.outputObjects,
        stats = stats
      )
    } finally {
      val endTime = System.nanoTime()

      if (graphStream.isDefined) graphStream.get.close()

      if (options.printSummary) {
        val tb = new TablePrinter(Some("COMPILER SUMMARY"))

        tb.addNamedLine("Model", modelName)
        layout.addTableLines(tb)
        tb.addNamedLine(
          "DRAM0 maximum usage (vectors/scalars)",
          mm.dram0Space.maxSize,
          mm.dram0Space.maxSize * options.arch.arraySize
        )
        tb.addNamedLine(
          "DRAM0 aggregate usage (vectors/scalars)",
          mm.dram0Space.aggSize,
          mm.dram0Space.aggSize * options.arch.arraySize
        )
        tb.addNamedLine(
          "DRAM1 maximum usage (vectors/scalars)",
          mm.dram1Space.maxSize,
          mm.dram1Space.maxSize * options.arch.arraySize
        )
        tb.addNamedLine(
          "DRAM1 aggregate usage (vectors/scalars)",
          mm.dram1Space.aggSize,
          mm.dram1Space.aggSize * options.arch.arraySize
        )
        if (mm.localSpace.maxSize != 0)
          tb.addNamedLine(
            "Local memory maximum usage (vectors/scalars)",
            mm.localSpace.maxSize,
            mm.localSpace.maxSize * options.arch.arraySize
          )
        if (mm.localSpace.aggSize != 0)
          tb.addNamedLine(
            "Local memory aggregate usage (vectors/scalars)",
            mm.localSpace.aggSize,
            mm.localSpace.aggSize * options.arch.arraySize
          )
        tb.addNamedLine("Number of layers", layerSchedulerResults.size)
        Stats.printSummary(
          backendStats,
          tb,
          options.arch,
          Some(macs)
        )
        tb.addNamedLine(
          "Total number of instructions",
          backend.instructionsCount
        )
        tb.addNamedLine(
          "Compilation time (seconds)",
          (endTime - startTime).toFloat / 1e9f
        )
        tb.addNamedLine("True consts scalar size", mm.constsScalarSize)
        tb.addNamedLine("Consts utilization (%)", mm.constsUtilization * 100f)
        val (macsLetter, macsDivisor) =
          Stats.getUnitsLetterAndDivisor(macs)
        tb.addNamedLine(
          s"True MACs (${macsLetter}MAC)",
          macs.toFloat / macsDivisor
        )
        tb.addNamedLine("MAC efficiency (%)", macEfficiency * 100f)
        print(tb)
      }

      if (options.printLayersSummary) {
        val layerSchedulerResultsWithIndex =
          layerSchedulerResults.zipWithIndex

        for (
          groupResultsWithIndex <- layerSchedulerResultsWithIndex.grouped(32)
        ) {
          val tb = new TablePrinter(Some("LAYERS SUMMARY"), true)
          tb.addLine(
            new TableLine(
              List("Layer:") ++ groupResultsWithIndex.map(_._2)
            )
          )
          tb.addLine(
            new TableLine(
              List(
                "Number of stages:"
              ) ++ groupResultsWithIndex
                .map(_._1.numberOfStages)
            )
          )
          tb.addLine(
            new TableLine(
              List(
                "Number of combined stages:"
              ) ++ groupResultsWithIndex
                .map(_._1.numberOfCombinedStages)
            )
          )
          tb.addLine(
            new TableLine(
              List(
                "Number of partitions:"
              ) ++ groupResultsWithIndex.map(_._1.numberOfPartitions)
            )
          )
          val (cyclesLetter, cyclesDivisor) =
            Stats.getUnitsLetterAndDivisor(
              groupResultsWithIndex
                .map(_._1.cycles)
                .max
            )
          tb.addLine(
            new TableLine(
              List(
                s"Latency (${cyclesLetter}Cycles):"
              ) ++ groupResultsWithIndex
                .map(_._1.cycles.toFloat)
                .map(_ / cyclesDivisor)
                .map(f => f"$f%.3f")
            )
          )
          val (energyLetter, energyDivisor) =
            Stats.getUnitsLetterAndDivisor(
              groupResultsWithIndex
                .map(_._1.energy)
                .max
            )
          tb.addLine(
            new TableLine(
              List(
                s"Energy (${energyLetter}Units):"
              ) ++ groupResultsWithIndex
                .map(_._1.energy.toFloat)
                .map(_ / energyDivisor)
                .map(f => f"$f%.3f")
            )
          )
          val (macsLetter, macsDivisor) =
            Stats.getUnitsLetterAndDivisor(
              groupResultsWithIndex
                .map(_._1.macs)
                .max
            )
          tb.addLine(
            new TableLine(
              List(
                s"True MACs (${macsLetter}MAC):"
              ) ++ groupResultsWithIndex
                .map(_._1.macs.toFloat)
                .map(_ / macsDivisor)
                .map(f => f"$f%.3f")
            )
          )
          tb.addLine(
            new TableLine(
              List(
                "MAC efficiency (%):"
              ) ++ groupResultsWithIndex
                .map(_._1.macEfficiency)
                .map(_ * 100f)
                .map(f => f"$f%.1f")
            )
          )
          tb.addLine(
            new TableLine(
              List(
                "Accumulator utilization (%):"
              ) ++ groupResultsWithIndex
                .map(_._1.accumulatorUtilization)
                .map(_ * 100f)
                .map(f => f"$f%.1f")
            )
          )
          tb.addLine(
            new TableLine(
              List("Local utilization (%):") ++ groupResultsWithIndex
                .map(_._1.localUtilization)
                .map(_ * 100f)
                .map(f => f"$f%.1f")
            )
          )
          print(tb)
        }
      }

      if (options.printInstructionsSummary) {
        Stats.printCompositionSummary("TOTAL", backendStats)
        Stats.printCyclesSummary("TOTAL", backendStats)
        Stats.printEnergySummary("TOTAL", backendStats)
      }

      if (options.printStridesSummary) {
        def printStrideStats(
            title: String,
            select: StrideStats => Any
        ): Unit = {
          val tb = new TablePrinter(Some(title), true)
          Stats.printStrideStats(
            options.arch.stride0Depth,
            options.arch.stride1Depth,
            backendStats,
            select,
            tb
          )
          print(tb)
        }

        printStrideStats(
          "TOTAL STRIDES COUNT SUMMARY",
          stats => stats.count
        )
        printStrideStats(
          "TOTAL STRIDES MAX SIZE SUMMARY",
          stats => stats.maxSize
        )
        printStrideStats(
          "TOTAL STRIDES AVERAGE SIZE SUMMARY",
          stats => Math.round(stats.totalSize.toFloat / stats.count.toFloat)
        )
      }

      options.arch.dataType.reportAndResetOverUnderflowStats()
    }
  }
}
