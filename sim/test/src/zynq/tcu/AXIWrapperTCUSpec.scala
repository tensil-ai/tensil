/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.zynq.tcu

import java.io.{ByteArrayOutputStream, ByteArrayInputStream, FileInputStream}

import scala.io.Source

import org.scalatest.tagobjects.Slow

import chisel3._
import chisel3.experimental.FixedPoint
// import chisel3.tester.experimental.TestOptionBuilder._

import chiseltest._
// import chiseltest.internal.VerilatorBackendAnnotation
import chiseltest.VerilatorBackendAnnotation

import tensil.{
  FunUnitSpec,
  PlatformConfig,
  Architecture,
  Fixed16bp8,
  ArchitectureDataType
}
import tensil.data.InstructionReader

import tensil.tools.{Compiler, CompilerOptions, CompilerInputShapes}
import tensil.tools.compiler.MemoryAddressHelper
import tensil.{InstructionLayout}

import tensil.axi
import tensil.tcu.{LocalDataFlowControl, TCUOptions}
import tensil.tcu.instruction.{
  Opcode,
  Instruction,
  DataMoveFlags,
  DataMoveArgs,
  DataMoveKind,
  ConfigureArgs,
  Configure
}
import tensil.mem.MemKind
import tensil.tools.{Util, ResNet}
import tensil.util.divCeil

class AXIWrapperTCUSpec extends FunUnitSpec {
  val debug          = false
  val randomizeDrams = !debug

  implicit val platformConfig =
    PlatformConfig.default.copy(memKind = MemKind.XilinxBlockRAM)

  def varyArchAndAXI[T <: Data with Num[T]](gen: T, arch: Architecture)(implicit
      axiConfig: axi.Config
  ) =
    describe(
      s"when axi=$axiConfig, gen=$gen, arch=$arch"
    ) {

      val layout = InstructionLayout(arch)

      def varyDramDelay(readDelayCycles: Int, writeDelayCycles: Int)(implicit
          axiConfig: axi.Config
      ) =
        describe(
          s"when DRAM rdelay=$readDelayCycles, wdelay=$writeDelayCycles"
        ) {
          // setup dram instances
          val dram0 = DRAM(
            arch = arch,
            index = 0,
            readDelayCycles = readDelayCycles,
            writeDelayCycles = writeDelayCycles,
            debug = debug
          )
          val dram1 = DRAM(
            arch = arch,
            index = 1,
            readDelayCycles = readDelayCycles,
            writeDelayCycles = writeDelayCycles,
            debug = debug
          )

          def xor4(batchSize: Int) =
            it(s"should run XOR4 network with $batchSize batch", Slow) {
              test(
                new AXIWrapperTCU(
                  gen,
                  layout,
                  AXIWrapperTCUOptions(dramAxiConfig = axiConfig)
                )
              ).withAnnotations(Seq(VerilatorBackendAnnotation)) { m =>
                m.setClocks()
                m.clock.setTimeout(1000000)

                implicit val layout: InstructionLayout =
                  m.setInstructionParameters()

                // compiler parameters
                val options = CompilerOptions(
                  arch = arch,
                  inputShapes = CompilerInputShapes.mkWithBatchSize(batchSize),
                )

                // setup compiler input/output streams
                val modelFileName = "./models/xor4.pb"
                val model         = new FileInputStream(modelFileName)
                val consts        = new ByteArrayOutputStream()
                val program       = new ByteArrayOutputStream()

                // run the compiler
                val compilerResult = Compiler.compileStreamToStreams(
                  "xor4_pb",
                  Compiler.getModelSourceType(modelFileName),
                  model,
                  List("Identity"),
                  program,
                  consts,
                  options
                )

                model.close()

                // write consts to dram1
                dram1.writeFromStream(
                  0,
                  new ByteArrayInputStream(consts.toByteArray)
                )

                if (debug) {
                  println("DRAM1 contents:")
                  println(dram1.dumpVectors(0, 20))
                }

                // drams to listen
                fork {
                  dram0.listen(m.clock, m.dram0)
                }
                fork {
                  dram1.listen(m.clock, m.dram1)
                }

                case class TestCase(x0: Int, x1: Int, y: Int)
                val testCases = Array(
                  TestCase(0, 0, 0),
                  TestCase(0, 1, 1),
                  TestCase(1, 0, 1),
                  TestCase(1, 1, 0),
                )

                for (batchTestCase <- testCases.grouped(batchSize)) {
                  if (debug) {
                    println("--------------------------------------------")
                    println(batchTestCase.mkString(" "))
                  }

                  // write input to dram0
                  for (i <- 0 until batchSize)
                    dram0.writeFloatVector(
                      compilerResult.inputObjects(0).span(i).raw,
                      Seq(
                        batchTestCase(i).x0.toFloat,
                        batchTestCase(i).x1.toFloat
                      )
                    )

                  if (debug) {
                    println("DRAM0 contents:")
                    println(dram0.dumpVectors(0, batchSize))
                  }

                  // feed compiler instructions output into TCU
                  fork {
                    val instructions =
                      new InstructionReader(
                        new ByteArrayInputStream(program.toByteArray),
                        layout.instructionSizeBytes
                      )
                    for (instruction <- instructions) {
                      m.instruction.enqueue(Instruction.fromUInt(instruction.U))
                    }
                  }

                  // wait until it's done
                  m.clock.step(3000)

                  // check output in DRAM
                  if (debug) {
                    println("DRAM0 contents:")
                    println(dram0.dumpVectors(0, batchSize))
                  }

                  for (i <- 0 until batchSize)
                    assertEqual(
                      dram0
                        .readFloatVector(
                          compilerResult.outputObjects(0).span(i).raw
                        )(0),
                      batchTestCase(i).y.toFloat,
                      arch.dataType.error
                    )
                }
              }
            }

          def resnet(batchSize: Int, inputSize: Int) =
            it(
              s"should run ResNet20 (CIFAR) network with $batchSize batch and $inputSize inputs",
              Slow
            ) {
              test(
                new AXIWrapperTCU(
                  gen,
                  layout,
                  AXIWrapperTCUOptions(dramAxiConfig = axiConfig)
                )
              ).withAnnotations(Seq(VerilatorBackendAnnotation)) { m =>
                m.setClocks()
                m.clock.setTimeout(Int.MaxValue)

                implicit val layout: InstructionLayout =
                  m.setInstructionParameters()

                // compiler parameters
                val options = CompilerOptions(
                  arch = arch,
                  inputShapes = CompilerInputShapes.mkWithBatchSize(batchSize),
                  //printProgramFileName = Some(s"sim_resnet20v2_cifar.tasm"),
                )

                // setup compiler output streams
                val modelFileName = "./models/resnet20v2_cifar.onnx"
                val model         = new FileInputStream(modelFileName)
                val consts        = new ByteArrayOutputStream()
                val program       = new ByteArrayOutputStream()

                // run the compiler
                val compilerResult = Compiler.compileStreamToStreams(
                  "resnet20v2_cifar_onnx",
                  Compiler.getModelSourceType(modelFileName),
                  model,
                  List("Identity:0"),
                  program,
                  consts,
                  options
                )

                model.close()

                // write consts to dram1
                dram1.writeFromStream(
                  0,
                  new ByteArrayInputStream(consts.toByteArray)
                )

                //println(dram1.dumpVectors(0, 32))

                // drams to listen
                fork {
                  dram0.listen(m.clock, m.dram0)
                }
                fork {
                  dram1.listen(m.clock, m.dram1)
                }

                // write input to dram0
                val source = Source.fromFile(
                  s"./models/data/resnet_input_${inputSize}x32x32x${m.layout.arch.arraySize}.csv"
                )
                val lines = source.getLines().toList
                source.close()

                var i = 0

                for (k <- 0 until inputSize / batchSize) {

                  for (a <- compilerResult.inputObjects(0).span) {
                    val pixel = lines(i).split(",").map(_.toFloat)

                    assert(pixel.size == m.layout.arch.arraySize)

                    dram0.writeFloatVector(
                      a.raw,
                      pixel
                    )

                    i += 1
                  }

                  //println(dram0.dumpVectors(compilerResult.inputObjects(0).span))

                  // feed compiler instructions output into TCU
                  fork {
                    val instructions =
                      new InstructionReader(
                        new ByteArrayInputStream(program.toByteArray),
                        layout.instructionSizeBytes
                      )

                    var pc = 0

                    for (instruction <- instructions) {
                      if (pc % 1000 == 0)
                        println(
                          s"PC: $pc, INSTRUCTION: ${instruction.toString(16)}"
                        )

                      m.instruction.enqueue(Instruction.fromUInt(instruction.U))

                      pc += 1
                    }
                  }

                  // wait until it's done
                  for (l <- 0 until 3700000 * batchSize) {

                    if (l % 1000 == 0)
                      println(s"CYCLE: $l")

                    m.clock.step()
                  }

                  println(
                    dram0.dumpVectors(
                      compilerResult
                        .outputObjects(0)
                        .span
                    )
                  )

                  val resultsSize =
                    divCeil(ResNet.ClassSize, m.layout.arch.arraySize)

                  for (l <- 0 until batchSize) {

                    val result =
                      for (m <- 0 until resultsSize)
                        yield dram0.readFloatVector(
                          compilerResult
                            .outputObjects(0)
                            .span(l * resultsSize + m)
                            .raw
                        )

                    assert(
                      Util.argMax(
                        result.flatten.take(ResNet.ClassSize).toArray
                      ) == ResNet.GoldenClasses(k * batchSize + l)
                    )
                  }
                }
              }
            }

          def dataMove(
              size: Int,
              untilShift: Int,
              asymmentric: Boolean
          ) =
            it(
              s"should DataMove with size=$size, shift=[0,$untilShift), asym=$asymmentric",
              Slow
            ) {
              test(
                new AXIWrapperTCU(
                  gen,
                  layout,
                  AXIWrapperTCUOptions(dramAxiConfig = axiConfig)
                )
              ).withAnnotations(Seq(VerilatorBackendAnnotation)) { m =>
                m.setClocks()
                m.clock.setTimeout(Int.MaxValue)

                implicit val layout: InstructionLayout =
                  m.setInstructionParameters()

                // drams to listen
                fork {
                  dram0.listen(m.clock, m.dram0)
                }
                fork {
                  dram1.listen(m.clock, m.dram1)
                }

                val fromDramBaseAddress = size.toLong
                val toDramBaseAddress =
                  fromDramBaseAddress + size.toLong

                val fromLocalBaseAddress = size.toLong
                val toLocalBaseAddress =
                  fromLocalBaseAddress + size.toLong

                val values =
                  Array.fill[Float](size * m.layout.arch.arraySize)(0)

                for (
                  shift0 <- 0 until untilShift; shift1 <- 0 until untilShift
                ) {
                  val (
                    fromDramShift,
                    toDramShift,
                    fromLocalShift,
                    toLocalShift
                  ) =
                    if (asymmentric) (shift0, shift1, shift0, shift1)
                    else (shift0, shift0, shift1, shift1)

                  val fromDramAddress  = fromDramBaseAddress + fromDramShift
                  val fromLocalAddress = fromLocalBaseAddress + fromLocalShift

                  val toLocalAddress = toLocalBaseAddress + toLocalShift
                  val toDramAddress  = toDramBaseAddress + toDramShift

                  println(
                    s"size=$size, fromDram=$fromDramAddress, toDram=$toDramAddress, fromLoc=$fromLocalAddress, toLoc=$toLocalAddress"
                  )

                  // write input to dram0
                  for (i <- 0 until values.size)
                    values(i) = Math.random().toFloat

                  for (i <- 0 until size)
                    dram0.writeFloatVector(
                      fromDramBaseAddress + fromDramShift + i,
                      values.slice(
                        i * m.layout.arch.arraySize,
                        (i + 1) * m.layout.arch.arraySize
                      )
                    )

                  // feed compiler instructions output into TCU
                  m.instruction.enqueue(
                    Instruction(
                      Opcode.DataMove,
                      DataMoveFlags(DataMoveKind.dram0ToMemory),
                      DataMoveArgs(
                        fromLocalAddress,
                        fromDramAddress,
                        size - 1
                      )
                    )
                  )
                  m.instruction.enqueue(
                    Instruction(
                      Opcode.DataMove,
                      DataMoveFlags(DataMoveKind.memoryToAccumulator),
                      DataMoveArgs(
                        fromLocalAddress,
                        0,
                        size - 1
                      )
                    )
                  )
                  m.instruction.enqueue(
                    Instruction(
                      Opcode.DataMove,
                      DataMoveFlags(DataMoveKind.accumulatorToMemory),
                      DataMoveArgs(
                        toLocalAddress,
                        0,
                        size - 1
                      )
                    )
                  )
                  m.instruction.enqueue(
                    Instruction(
                      Opcode.DataMove,
                      DataMoveFlags(DataMoveKind.memoryToDram0),
                      DataMoveArgs(
                        toLocalAddress,
                        toDramAddress,
                        size - 1
                      )
                    )
                  )

                  // wait until it's done
                  m.clock.step(1000)

                  for (i <- 0 until size) {
                    val expected = values.slice(
                      i * m.layout.arch.arraySize,
                      (i + 1) * m.layout.arch.arraySize
                    )

                    var read = dram0.readFloatVector(
                      toDramBaseAddress + toDramShift + i,
                    )

                    for (k <- 0 until m.layout.arch.arraySize)
                      assertEqual(
                        read(k),
                        expected(k),
                        m.layout.arch.dataType.error
                      )
                  }
                }
              }
            }

          def sample(
              programSize: Int,
              interval: Int,
              blockSize: Int
          ) =
            it(
              s"should sample with size=$programSize, interval=$interval, blockSize=$blockSize",
              Slow
            ) {
              test(
                new AXIWrapperTCU(
                  gen,
                  layout,
                  AXIWrapperTCUOptions(
                    inner = TCUOptions(sampleBlockSize = blockSize),
                    dramAxiConfig = axiConfig
                  )
                )
              ).withAnnotations(Seq(VerilatorBackendAnnotation)) { m =>
                m.setClocks()
                m.clock.setTimeout(Int.MaxValue)

                implicit val layout: InstructionLayout =
                  m.setInstructionParameters()

                val cycleCount = 3 * programSize

                // drams to listen
                fork {
                  dram0.listen(m.clock, m.dram0)
                }
                fork {
                  dram1.listen(m.clock, m.dram1)
                }
                val t0 = fork {
                  m.sample.ready.poke(true.B)

                  val samples = (0 until cycleCount / interval).map({
                    case 0 => (1, false)
                    case x =>
                      (
                        Math.min(x * interval - 1, 1000),
                        (x + 1) % blockSize == 0
                      )
                  })

                  for ((programCounter, last) <- samples) {
                    m.sample.waitForValid()
                    m.sample.bits.bits.programCounter.expect(programCounter.U)
                    m.sample.bits.last.expect(last.B)

                    m.clock.step()
                  }
                }
                val t1 = fork {
                  var pc = 0

                  m.instruction.enqueue(
                    Instruction(
                      Opcode.Configure,
                      ConfigureArgs(Configure.sampleInterval, interval)
                    )
                  )

                  m.instruction.enqueue(
                    Instruction(
                      Opcode.Configure,
                      ConfigureArgs(Configure.programCounter, 0)
                    )
                  )

                  for (_ <- 0 until programSize) {
                    m.instruction.enqueue(Instruction(Opcode.NoOp))
                  }
                }

                t0.join()
                t1.join()
              }
            }

          val dataMoveSizes =
            (1 to 7)
              .map(Math.pow(2, _).toInt)
              .map(v => Seq(v - 1, v, v + 1))
              .flatten
              .distinct
              .filter(_ <= arch.accumulatorDepth)

          val tests =
            Seq(
              () => sample(programSize = 1000, interval = 10, blockSize = 16),
              () => xor4(batchSize = 1),
              () => xor4(batchSize = 2),
              () => xor4(batchSize = 4),
              () => resnet(batchSize = 1, inputSize = 1),
              () => resnet(batchSize = 10, inputSize = 10),
            ) ++ dataMoveSizes.map(size => () => dataMove(size, 4, true)) ++
              dataMoveSizes.map(size => () => dataMove(size, 4, false))

          for (t <- tests) {
            if (randomizeDrams) {
              dram0.randomize()
              dram1.randomize()
            } else {
              dram0.zero()
              dram1.zero()
            }

            t()
          }
        }

      varyDramDelay(0, 0)
      /*varyDramDelay(10, 0)
      varyDramDelay(0, 10)
      varyDramDelay(10, 10)*/
    }

  describe("AXIWrapperTCU") {
    val arch2x2fp16bp8 = Architecture.mkWithDefaults(
      dataType = ArchitectureDataType.FP16BP8,
      arraySize = 2,
      accumulatorDepth = 128,
      localDepth = 128,
      dram1Depth = 128,
      dram0Depth = 128,
      stride0Depth = 2,
      stride1Depth = 2,
    )

    val arch8x8fp16bp8 = Architecture.mkWithDefaults(
      dataType = ArchitectureDataType.FP16BP8,
      arraySize = 8,
      accumulatorDepth = 2048,
      localDepth = 8192,
      dram0Depth = 1048576,
      dram1Depth = 1048576,
      stride0Depth = 8,
      stride1Depth = 8,
    )

    val arch8x8fp18bp10 = Architecture.mkWithDefaults(
      dataType = ArchitectureDataType.FP18BP10,
      arraySize = 8,
      accumulatorDepth = 2048,
      localDepth = 8192,
      dram0Depth = 1048576,
      dram1Depth = 1048576,
      stride0Depth = 8,
      stride1Depth = 8,
    )

    val arch8x8fp32bp16 = Architecture.mkWithDefaults(
      dataType = ArchitectureDataType.FP32BP16,
      arraySize = 8,
      accumulatorDepth = 2048,
      localDepth = 8192,
      dram0Depth = 1048576,
      dram1Depth = 1048576,
      stride0Depth = 8,
      stride1Depth = 8,
    )

    val fp16bp8  = FixedPoint(16.W, 8.BP)
    val fp18bp10 = FixedPoint(18.W, 10.BP)
    val fp32bp16 = FixedPoint(32.W, 16.BP)

    /*varyArchAndAXI(fp16bp8, arch2x2fp16bp8)(axi.Config.Xilinx)
    varyArchAndAXI(fp16bp8, arch2x2fp16bp8)(axi.Config.Xilinx64)

    varyArchAndAXI(fp16bp8, arch8x8fp16bp8)(axi.Config.Xilinx)
    varyArchAndAXI(fp16bp8, arch8x8fp16bp8)(axi.Config.Xilinx64)*/
    varyArchAndAXI(fp16bp8, arch8x8fp16bp8)(axi.Config.Xilinx128)
    /*varyArchAndAXI(fp16bp8, arch8x8fp16bp8)(axi.Config.Xilinx256)

    varyArchAndAXI(fp18bp10, arch8x8fp18bp10)(axi.Config.Xilinx)
    varyArchAndAXI(fp18bp10, arch8x8fp18bp10)(axi.Config.Xilinx64)
    varyArchAndAXI(fp18bp10, arch8x8fp18bp10)(axi.Config.Xilinx128)
    varyArchAndAXI(fp18bp10, arch8x8fp18bp10)(axi.Config.Xilinx256)

    varyArchAndAXI(fp32bp16, arch8x8fp32bp16)(axi.Config.Xilinx)
    varyArchAndAXI(fp32bp16, arch8x8fp32bp16)(axi.Config.Xilinx64)
    varyArchAndAXI(fp32bp16, arch8x8fp32bp16)(axi.Config.Xilinx128)
    varyArchAndAXI(fp32bp16, arch8x8fp32bp16)(axi.Config.Xilinx256)*/
  }

  private def assertEqual(
      y: Float,
      yExpected: Float,
      tolerance: Float
  ): Unit = {
    assert(
      y < (yExpected + tolerance) && y > (yExpected - tolerance),
      s"expected = $yExpected, actual = $y"
    )
  }

}
