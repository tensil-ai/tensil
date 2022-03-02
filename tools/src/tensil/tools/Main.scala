package tensil.tools

import java.io.{DataOutputStream, FileOutputStream}
import tensil.{Architecture, ArchitectureDataType, InstructionLayout}

import java.io.File
import tensil.TablePrinter

case class Args(
    archFile: File = new File("."),
    modelFile: File = new File("."),
    outputNodes: Seq[String] = Seq("Identity"),
    inputBatchSize: Int = 1,
    verbose: Boolean = false,
    summary: Boolean = false,
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
        .valueName("<names>")
        .action((x, c) => c.copy(outputNodes = x))
        .text("Optional list of output nodes, defaults to \"Identity\"")

      opt[Int]('b', "batch")
        .valueName("<integer>")
        .action((x, c) => c.copy(inputBatchSize = x))
        .text("Optional size of input batch, defaults to 1")

      opt[Boolean]('v', "verbose")
        .valueName("true|false")
        .action((x, c) => c.copy(verbose = x))

      opt[Boolean]('s', "summary")
        .valueName("true|false")
        .action((x, c) => c.copy(summary = x)),
  }

  argParser.parse(args, Args()) match {
    case Some(args) =>
      val arch = Architecture.read(args.archFile)

      val options = CompilerOptions(
        arch = arch,
        inputBatchSize = args.inputBatchSize,
        printProgress = args.verbose,
        printSummary = args.summary,
      )

      val archName = args.archFile.getName().split("\\.")(0)
      val modelName =
        args.modelFile.getName().replaceAll("[^a-zA-Z\\d\\s:]", "_")

      Compiler.compile(
        s"${modelName}_${archName}",
        args.modelFile.getPath(),
        args.outputNodes,
        options
      )

      val tb = new TablePrinter(Some("ARTIFACTS"))

      tb.addNamedLine(
        "Manifest",
        new File(s"${modelName}.tmodel").getAbsolutePath()
      )
      tb.addNamedLine(
        "Constants",
        new File(s"${modelName}.tdata").getAbsolutePath()
      )
      tb.addNamedLine(
        "Program",
        new File(s"${modelName}.tprog").getAbsolutePath()
      )

      print(tb)

    case _ =>
      sys.exit(1)
  }
}
