/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.emulator

import java.io.{
  File,
  FileInputStream,
  DataInputStream,
  DataOutputStream,
  FileOutputStream,
  ByteArrayInputStream,
  ByteArrayOutputStream
}

import scala.reflect.ClassTag
import scala.collection.mutable

import tensil.{
  Architecture,
  ArchitectureDataType,
  ArchitectureDataTypeWithBase,
  InstructionLayout,
  FloatAsIfIntegralWithMAC,
  NumericWithMAC
}
import tensil.tools.ArchitectureDataTypeUtil
import tensil.tools.model.Model

case class Args(
    modelFile: File = new File("."),
    inputFiles: Seq[File] = Nil,
    outputFiles: Seq[File] = Nil,
)

object Main extends App {
  val argParser = new scopt.OptionParser[Args]("compile") {
    help("help").text("Prints this usage text")

    opt[File]('m', "model")
      .required()
      .valueName("<file>")
      .action((x, c) => c.copy(modelFile = x))
      .text("Tensil model (.tmodel) file")

    opt[Seq[File]]('i', "inputs")
      .required()
      .valueName("<file>, ...")
      .action((x, c) => c.copy(inputFiles = x))
      .text("Input (.csv) files")

    opt[Seq[File]]('o', "outputs")
      .required()
      .valueName("<file>, ...")
      .action((x, c) => c.copy(outputFiles = x))
      .text("Output (.csv) files")
  }

  argParser.parse(args, Args()) match {
    case Some(args) =>
      val modelStream = new FileInputStream(args.modelFile)
      val model       = upickle.default.read[Model](modelStream)
      modelStream.close()

      val traceContext = ExecutiveTraceContext.default

      model.arch.dataType.name match {
        case ArchitectureDataType.FLOAT32.name =>
          implicit val numericWithMAC = FloatAsIfIntegralWithMAC

          doEmulate(
            ArchitectureDataType.FLOAT32,
            model,
            args.inputFiles,
            args.outputFiles,
            traceContext
          )

        case ArchitectureDataType.FP32BP16.name =>
          doEmulate(
            ArchitectureDataType.FP32BP16,
            model,
            args.inputFiles,
            args.outputFiles,
            traceContext
          )

        case ArchitectureDataType.FP18BP10.name =>
          doEmulate(
            ArchitectureDataType.FP18BP10,
            model,
            args.inputFiles,
            args.outputFiles,
            traceContext
          )

        case ArchitectureDataType.FP16BP8.name =>
          doEmulate(
            ArchitectureDataType.FP16BP8,
            model,
            args.inputFiles,
            args.outputFiles,
            traceContext
          )

        case ArchitectureDataType.FP8BP4.name =>
          doEmulate(
            ArchitectureDataType.FP8BP4,
            model,
            args.inputFiles,
            args.outputFiles,
            traceContext
          )
      }

    case _ =>
      sys.exit(1)
  }

  private def doEmulate[T : NumericWithMAC : ClassTag](
      dataType: ArchitectureDataTypeWithBase[T],
      model: Model,
      inputFiles: Seq[File],
      outputFiles: Seq[File],
      traceContext: ExecutiveTraceContext
  ): Unit = {
    val emulator = new Emulator(
      dataType = dataType,
      arch = model.arch
    )

    emulator.writeDRAM1(
      model.consts(0).size,
      new FileInputStream(model.consts(0).fileName)
    )

    require(model.inputs.size == inputFiles.size)

    for ((input, file) <- model.inputs.zip(inputFiles)) {
      val inputPrep           = new ByteArrayOutputStream()
      val inputPrepDataStream = new DataOutputStream(inputPrep)

      ArchitectureDataTypeUtil.writeFromCsv(
        dataType,
        inputPrepDataStream,
        model.arch.arraySize,
        file.getAbsolutePath()
      )

      require(
        inputPrep.size() == input.size * model.arch.arraySize * dataType.sizeBytes
      )

      val inputStream = new DataInputStream(
        new ByteArrayInputStream(inputPrep.toByteArray())
      )
      emulator.writeDRAM0(
        input.base until input.base + input.size,
        inputStream
      )
    }

    val trace         = new ExecutiveTrace(traceContext)
    val programStream = new FileInputStream(model.program.fileName)

    emulator.run(programStream, trace)

    programStream.close()
    trace.printTrace()

    require(model.outputs.size == outputFiles.size)

    for ((output, file) <- model.outputs.zip(outputFiles)) {
      val outputPrep           = new ByteArrayOutputStream()
      val outputPrepDataStream = new DataOutputStream(outputPrep)

      emulator.readDRAM0(
        output.base until output.base + output.size,
        outputPrepDataStream
      )

      val outputStream = new DataInputStream(
        new ByteArrayInputStream(outputPrep.toByteArray())
      )

      ArchitectureDataTypeUtil.readToCsv(
        dataType,
        outputStream,
        model.arch.arraySize,
        output.size,
        file.getAbsolutePath()
      )
    }
  }
}
