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
import scala.io.Source

import tensil.{
  Architecture,
  ArchitectureDataType,
  ArchitectureDataTypeWithBase,
  InstructionLayout,
  FloatAsIfIntegralWithMAC,
  NumericWithMAC,
  TablePrinter,
  TableLine
}
import tensil.tools.ArchitectureDataTypeUtil
import tensil.tools.model.Model

case class Args(
    modelFile: File = new File("."),
    inputFiles: Seq[File] = Nil,
    outputFiles: Seq[File] = Nil,
    compareFiles: Seq[File] = Nil,
    numberOfRuns: Int = 1,
    localConsts: Boolean = false,
)

object Main extends App {
  private val ANSI_RESET = "\u001B[0m"
  private val ANSI_RED   = "\u001B[31m"
  private val ANSI_GREEN = "\u001B[32m"

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
      .valueName("<file>, ...")
      .action((x, c) => c.copy(outputFiles = x))
      .text("Output (.csv) files")

    opt[Seq[File]]('c', "compare")
      .valueName("<file>, ...")
      .action((x, c) => c.copy(compareFiles = x))
      .text("Compare output (.csv) files")

    opt[Int]('r', "number-of-runs")
      .valueName("<integer>")
      .action((x, c) => c.copy(numberOfRuns = x))
      .text("Number of runs")

    opt[Boolean]("local-consts")
      .valueName("true|false")
      .action((x, c) => c.copy(localConsts = x))
      .text("Preload consts to local memory, defaults to false")
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
            args.compareFiles,
            args.numberOfRuns,
            args.localConsts,
            traceContext
          )

        case ArchitectureDataType.FP32BP16.name =>
          doEmulate(
            ArchitectureDataType.FP32BP16,
            model,
            args.inputFiles,
            args.outputFiles,
            args.compareFiles,
            args.numberOfRuns,
            args.localConsts,
            traceContext
          )

        case ArchitectureDataType.FP18BP10.name =>
          doEmulate(
            ArchitectureDataType.FP18BP10,
            model,
            args.inputFiles,
            args.outputFiles,
            args.compareFiles,
            args.numberOfRuns,
            args.localConsts,
            traceContext
          )

        case ArchitectureDataType.FP16BP8.name =>
          doEmulate(
            ArchitectureDataType.FP16BP8,
            model,
            args.inputFiles,
            args.outputFiles,
            args.compareFiles,
            args.numberOfRuns,
            args.localConsts,
            traceContext
          )

        case ArchitectureDataType.FP8BP4.name =>
          doEmulate(
            ArchitectureDataType.FP8BP4,
            model,
            args.inputFiles,
            args.outputFiles,
            args.compareFiles,
            args.numberOfRuns,
            args.localConsts,
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
      compareFiles: Seq[File],
      numberOfRuns: Int,
      localConsts: Boolean,
      traceContext: ExecutiveTraceContext
  ): Unit = {
    require(model.inputs.size == inputFiles.size)
    require(outputFiles.size == 0 || model.outputs.size == outputFiles.size)

    val emulator = new Emulator(
      dataType = dataType,
      arch = model.arch
    )

    val constsStream = new FileInputStream(model.consts(0).fileName)

    if (localConsts)
      emulator.writeLocal(
        model.consts(0).size,
        constsStream
      )
    else
      emulator.writeDRAM1(
        model.consts(0).size,
        constsStream
      )

    constsStream.close()

    val inputStreams =
      for ((input, file) <- model.inputs.zip(inputFiles)) yield {
        val inputPrep           = new ByteArrayOutputStream()
        val inputPrepDataStream = new DataOutputStream(inputPrep)

        ArchitectureDataTypeUtil.writeFromCsv(
          dataType,
          inputPrepDataStream,
          model.arch.arraySize,
          file.getAbsolutePath()
        )

        require(
          inputPrep
            .size() == input.size * model.arch.arraySize * dataType.sizeBytes * numberOfRuns
        )

        (
          input,
          new DataInputStream(
            new ByteArrayInputStream(inputPrep.toByteArray())
          )
        )
      }

    val outputPreps =
      if (outputFiles.size != 0)
        for ((output, file) <- model.outputs.zip(outputFiles)) yield {
          val outputPrep = new ByteArrayOutputStream()
          (
            output,
            Some((false, file)),
            outputPrep,
            new DataOutputStream(outputPrep)
          )
        }
      else if (compareFiles.size != 0)
        for ((output, file) <- model.outputs.zip(compareFiles)) yield {
          val outputPrep = new ByteArrayOutputStream()
          (
            output,
            Some((true, file)),
            outputPrep,
            new DataOutputStream(outputPrep)
          )
        }
      else
        for (output <- model.outputs) yield {
          val outputPrep = new ByteArrayOutputStream()
          (output, None, outputPrep, new DataOutputStream(outputPrep))
        }

    for (_ <- 0 until numberOfRuns) {
      val trace         = new ExecutiveTrace(traceContext)
      val programStream = new FileInputStream(model.program.fileName)

      for ((input, inputStream) <- inputStreams)
        emulator.writeDRAM0(
          input.base until input.base + input.size,
          inputStream
        )

      emulator.run(programStream, trace)

      for ((output, _, _, outputPrepDataStream) <- outputPreps)
        emulator.readDRAM0(
          output.base until output.base + output.size,
          outputPrepDataStream
        )

      programStream.close()
      trace.printTrace()
    }

    for ((output, compareAndFile, outputPrep, _) <- outputPreps) {
      val outputStream = new DataInputStream(
        new ByteArrayInputStream(outputPrep.toByteArray())
      )

      if (compareAndFile.isDefined)
        if (compareAndFile.get._1) {
          val source = Source.fromFile(compareAndFile.get._2.getAbsolutePath())
          val compare =
            source.getLines().map(_.split(",").map(_.toFloat)).flatten.toArray
          source.close()

          val result = ArchitectureDataTypeUtil.readResult(
            dataType,
            outputStream,
            model.arch.arraySize,
            output.size.toInt * numberOfRuns * model.arch.arraySize
          )

          require(result.size == compare.size)

          for (i <- 0 until numberOfRuns) {
            val tb = new TablePrinter(Some(s"COMPARE ${output.name}, RUN ${i}"))

            for (j <- 0 until output.size.toInt) {
              val offset = (i * output.size.toInt + j) * model.arch.arraySize
              val resultVector =
                result.slice(offset, offset + model.arch.arraySize)
              val compareVector =
                compare.slice(offset, offset + model.arch.arraySize)

              val withDeltas = resultVector
                .zip(compareVector)
                .map {
                  case (resultScalar, compareScalar) =>
                    (resultScalar, compareScalar, resultScalar - compareScalar)
                }

              if (withDeltas.exists(t => Math.abs(t._3) > dataType.error)) {
                tb
                  .addLine(
                    TableLine(
                      f"${j}%08d",
                      withDeltas
                        .grouped(8)
                        .map(_.map({
                          case (resultScalar, compareScalar, delta) => {
                            val mismatch = Math.abs(delta) > dataType.error
                            val s =
                              if (mismatch) f"($delta%.4f)"
                              else f"$resultScalar%.4f"
                            val sColored = (if (mismatch) ANSI_RED
                                            else ANSI_GREEN) + s + ANSI_RESET
                            " " * (12 - s.length()) + sColored
                          }
                        }).mkString)
                        .toIterable
                    )
                  )
              }
            }

            print(tb.toString())
          }
        } else {
          ArchitectureDataTypeUtil.readToCsv(
            dataType,
            outputStream,
            model.arch.arraySize,
            output.size * numberOfRuns,
            compareAndFile.get._2.getAbsolutePath()
          )
        }
      else {
        val r = ArchitectureDataTypeUtil.readResult(
          dataType,
          outputStream,
          model.arch.arraySize,
          output.size.toInt * numberOfRuns * model.arch.arraySize
        )

        for (i <- 0 until numberOfRuns) {
          val tb = new TablePrinter(Some(s"OUTPUT ${output.name}, RUN ${i}"))

          for (j <- 0 until output.size.toInt) {
            val offset = (i * output.size.toInt + j) * model.arch.arraySize
            val vector = r.slice(offset, offset + model.arch.arraySize)

            tb.addLine(
              TableLine(
                f"${j}%08d",
                vector
                  .grouped(8)
                  .map(_.map(v => {
                    val s = f"$v%.4f"
                    " " * (12 - s.length()) + s
                  }).mkString)
                  .toIterable
              )
            )
          }

          print(tb.toString())
        }
      }
    }
  }
}
