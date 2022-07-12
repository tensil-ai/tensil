/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io.{DataOutputStream, FileOutputStream, File}
import java.nio.file.{Paths, Files}

import tensil.{
  Architecture,
  ArchitectureDataType,
  InstructionLayout,
  TablePrinter
}
import tensil.tools.{
  Compiler,
  CompilerOptions,
  CompilerStrategy,
  CompilerInputShapes,
  CompilerException
}

case class Args(
    archFile: File = new File("."),
    modelFile: File = new File("."),
    outputNodes: Seq[String] = Seq("Identity"),
    inputShapes: String = "[1]",
    verbose: Boolean = false,
    summary: Boolean = false,
    layersSummary: Boolean = false,
    schedulerSummary: Boolean = false,
    partitionsSummary: Boolean = false,
    stridesSummary: Boolean = false,
    instructionsSummary: Boolean = false,
    writeGraph: Boolean = false,
    writeProgramAssembly: Boolean = false,
    targetDir: File = new File("."),
    strategy: CompilerStrategy.Kind = CompilerStrategy.LocalIsolated,
)

object Main extends App {
  val argParser = new scopt.OptionParser[Args]("compile") {
    help("help").text("Prints this usage text")

    opt[File]('m', "model")
      .required()
      .valueName("<file>")
      .action((x, c) => c.copy(modelFile = x))
      .text("Tensorflow frozen graph (.pb) or ONNX model (.onnx) file")

    opt[File]('a', "arch")
      .required()
      .valueName("<file>")
      .action((x, c) => c.copy(archFile = x))
      .text("Tensil architecture descrition (.tarch) file")

    opt[Seq[String]]('o', "output")
      .valueName("<name>, ...")
      .action((x, c) => c.copy(outputNodes = x))
      .text("Optional list of output nodes, defaults to \"Identity\"")

    opt[String]('i', "input-shapes")
      .valueName("<name> [<dim>, ...], ...")
      .action((x, c) => c.copy(inputShapes = x))
      .text(
        "Optional input shapes, defaults to \"[1]\" (batch size of 1). The shape without <name> is a default for inputs that were not listed by name"
      )

    opt[Boolean]('v', "verbose")
      .valueName("true|false")
      .action((x, c) => c.copy(verbose = x))

    opt[Boolean]('s', "summary")
      .valueName("true|false")
      .action((x, c) => c.copy(summary = x))
      .text("Print summary, defaults to false")

    opt[Boolean]("layers-summary")
      .valueName("true|false")
      .action((x, c) => c.copy(layersSummary = x))
      .text("Print layer summary, defaults to false")

    opt[Boolean]("scheduler-summary")
      .valueName("true|false")
      .action((x, c) => c.copy(schedulerSummary = x))
      .text("Print scheduler summary, defaults to false")

    opt[Boolean]("partitions-summary")
      .valueName("true|false")
      .action((x, c) => c.copy(partitionsSummary = x))
      .text("Print partitions summary, defaults to false")

    opt[Boolean]("strides-summary")
      .valueName("true|false")
      .action((x, c) => c.copy(stridesSummary = x))
      .text("Print strides summary, defaults to false")

    opt[Boolean]("instructions-summary")
      .valueName("true|false")
      .action((x, c) => c.copy(instructionsSummary = x))
      .text("Print instructions summary, defaults to false")

    opt[Boolean]("write-graph")
      .valueName("true|false")
      .action((x, c) => c.copy(writeGraph = x))
      .text("Write graph in dot format")

    opt[Boolean]("write-program-assembly")
      .valueName("true|false")
      .action((x, c) => c.copy(writeProgramAssembly = x))
      .text("Write program assembly")

    opt[File]('t', "target")
      .valueName("<dir>")
      .action((x, c) => c.copy(targetDir = x))
      .text("Optional target directory")

    opt[String]("strategy")
      .valueName("local-isolated|local-vars|local-consts|local-vars-and-consts")
      .action((x, c) =>
        c.copy(strategy = x match {
          case "local-isolated"        => CompilerStrategy.LocalIsolated
          case "local-vars"            => CompilerStrategy.LocalVars
          case "local-consts"          => CompilerStrategy.LocalConsts
          case "local-vars-and-consts" => CompilerStrategy.LocalVarsAndConsts
        })
      )
      .text("Local memory strategy, defaults to local-isolated")
  }

  argParser.parse(args, Args()) match {
    case Some(args) =>
      val arch     = Architecture.read(args.archFile)
      val archName = args.archFile.getName().split("\\.")(0)
      val modelName =
        s"${args.modelFile.getName().replaceAll("[^a-zA-Z\\d\\s:]", "_")}_${archName}"

      val targetDir = args.targetDir.getCanonicalPath()
      Files.createDirectories(Paths.get(targetDir))

      val options = CompilerOptions(
        arch = arch,
        strategy = args.strategy,
        inputShapes = CompilerInputShapes.parse(args.inputShapes),
        printProgress = args.verbose,
        printSummary = args.summary,
        printLayersSummary = args.layersSummary,
        printSchedulerSummary = args.schedulerSummary,
        printPartitionsSummary = args.partitionsSummary,
        printStridesSummary = args.stridesSummary,
        printInstructionsSummary = args.instructionsSummary,
        printGraph = args.writeGraph,
        printProgramAssembly = args.writeProgramAssembly,
        targetPath = Some(targetDir)
      )

      try {
        val r = Compiler.compile(
          modelName,
          args.modelFile.getPath(),
          args.outputNodes,
          options
        )

        val tb = new TablePrinter(Some("ARTIFACTS"))

        for (artifact <- r.artifacts)
          tb.addNamedLine(
            artifact.kind,
            new File(artifact.fileName).getAbsolutePath()
          )

        print(tb)
      } catch {
        case e: CompilerException =>
          println(s"Error: ${e.getMessage()}")
          sys.exit(1)
      }

    case _ =>
      sys.exit(1)
  }
}
