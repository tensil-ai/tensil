package tensil.tools

import java.io._
import org.scalatest._
import org.scalatest.tagobjects.Slow
import org.tensorflow.framework.types.DataType
import tensil.{ArchitectureDataType, Architecture}
import tensil.tools.golden.ExecutiveTraceContext
import tensil.tools.compiler.{MemoryDimensions, MemoryTag}

class CompilerSpec extends FlatSpec {
  behavior of "Compiler"

  val Models = "../tensil-models"

  def ConstsFileName(name: String)  = s"$name.tdata"
  def ProgramFileName(name: String) = s"$name.tprog"

  def getConstsBytes(name: String): Array[Byte] = {
    new FileInputStream(ConstsFileName(name)).readAllBytes()
  }

  def getProgramBytes(name: String): Array[Byte] = {
    new FileInputStream(ProgramFileName(name)).readAllBytes()
  }

  val Kibi = 1024
  val Mebi = Kibi * Kibi

  val Tiny2x2Architecure = Architecture.mkWithDefaults(
    dataType = ArchitectureDataType.FP16BP8,
    arraySize = 2,
    dram1Depth = 256,
    dram0Depth = 256,
    accumulatorDepth = 256,
    localDepth = 256,
  )
  val Tiny4x4Architecure = Architecture.mkWithDefaults(
    dataType = ArchitectureDataType.FP16BP8,
    arraySize = 4,
    dram1Depth = 256,
    dram0Depth = 256,
    accumulatorDepth = 256,
    localDepth = 256,
  )
  val Tiny8x8Architecure = Architecture.mkWithDefaults(
    dataType = ArchitectureDataType.FP16BP8,
    arraySize = 8,
    dram1Depth = 256,
    dram0Depth = 256,
    accumulatorDepth = 256,
    localDepth = 256,
  )
  val Large2x2Architecure = Architecture.mkWithDefaults(
    dataType = ArchitectureDataType.FP16BP8,
    arraySize = 2,
    dram1Depth = Kibi * 64,
    dram0Depth = Kibi * 64,
    accumulatorDepth = Kibi * 64,
    localDepth = Kibi * 64,
  )
  val Large2x2WithStridesArchitecure = Architecture.mkWithDefaults(
    dataType = ArchitectureDataType.FP16BP8,
    arraySize = 2,
    dram1Depth = 128,
    dram0Depth = 128,
    accumulatorDepth = 128,
    localDepth = 128,
    stride0Depth = 2,
    stride1Depth = 2,
  )
  val Large8x8Architecure = Architecture.mkWithDefaults(
    dataType = ArchitectureDataType.FP16BP8,
    arraySize = 8,
    dram1Depth = Kibi * 64,
    dram0Depth = Kibi * 64,
    accumulatorDepth = Kibi * 64,
    localDepth = Kibi * 64,
  )

  it should "Compile TF XOR for 2x2 array with 256 memories and input batch of 1" in {
    val name         = "xor_2x2_memory256"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = Tiny2x2Architecure,
      printSummary = true,
      printProgramFileName = Some(s"${name}.tasm"),
      printProgramWithComments = true,
      printGraphFileName = Some(s"${name}.dot"),
      tracepointConditions = List(
        TracepointCondition(MemoryTag.Vars, "x"),
        TracepointCondition(MemoryTag.Local, "x"),
        TracepointCondition(MemoryTag.Accumulators, "x"),
        TracepointCondition(MemoryTag.Vars, "sequential_10/dense_23/BiasAdd"),
        TracepointCondition(MemoryTag.Local, "sequential_10/dense_23/BiasAdd"),
        TracepointCondition(
          MemoryTag.Accumulators,
          "sequential_10/dense_23/BiasAdd"
        ),
        TracepointCondition(MemoryTag.Vars, "sequential_10/dense_23/Relu"),
        TracepointCondition(MemoryTag.Local, "sequential_10/dense_23/Relu"),
        TracepointCondition(
          MemoryTag.Accumulators,
          "sequential_10/dense_23/Relu"
        ),
        TracepointCondition(MemoryTag.Vars, "sequential_10/dense_23/Relu"),
        TracepointCondition(MemoryTag.Local, "sequential_10/dense_23/Relu"),
        TracepointCondition(
          MemoryTag.Accumulators,
          "sequential_10/dense_23/Relu"
        ),
        TracepointCondition(MemoryTag.Vars, "sequential_10/dense_24/BiasAdd"),
        TracepointCondition(MemoryTag.Local, "sequential_10/dense_24/BiasAdd"),
        TracepointCondition(
          MemoryTag.Accumulators,
          "sequential_10/dense_24/BiasAdd"
        ),
        TracepointCondition(
          MemoryTag.Consts,
          "sequential_10/dense_23/BiasAdd/ReadVariableOp"
        ),
        TracepointCondition(
          MemoryTag.Consts,
          "sequential_10/dense_23/MatMul/ReadVariableOp"
        ),
        TracepointCondition(
          MemoryTag.Consts,
          "sequential_10/dense_24/BiasAdd/ReadVariableOp"
        ),
        TracepointCondition(
          MemoryTag.Consts,
          "sequential_10/dense_24/MatMul/ReadVariableOp"
        )
      )
    )

    Compiler.compile(
      name,
      s"$Models/xor.pb",
      List("Identity"),
      options,
      traceContext
    )

    assert(
      getConstsBytes(name) === Array(
        // sequential_10/dense_23/BiasAdd/ReadVariableOp
        // 0,0
        0x0, 0x0,
        // 0,1
        0xe3, 0xff,
        // sequential_10/dense_23/MatMul/ReadVariableOp
        // 0,0
        0x2e, 0xff,
        // 0,1
        0xe9, 0x0,
        // 1,0
        0xd2, 0x0,
        // 1,1
        0x34, 0xff,
        // sequential_10/dense_24/BiasAdd/ReadVariableOp
        // 0,0
        0x1, 0x0,
        // 0,1
        0x0, 0x0,
        // sequential_10/dense_24/MatMul/ReadVariableOp
        // 0,0
        0x35, 0x1,
        // 0,1
        0x0, 0x0,
        // 1,0
        0x3e, 0x1,
        // 1,1
        0x0, 0x0,
      ).map(_.toByte)
    )

    assert(
      getProgramBytes(name) === Array(
        0x00, 0x00, 0x02, 0x22, // DataMove(<-) Local(0) Consts(0) 2(+1)
        0x03, 0x00, 0x00, 0x20, // DataMove(<-) Local(3) Vars(0)
        0x00, 0x02, 0x00, 0x30, // LoadWeights  Local(0) 2(+1)
        0x03, 0x00, 0x00, 0x10, // MatMul       Local(3) Acc(0)
        0x00, 0x00, 0x09, 0x40, // SIMD         R1=0
        0x01, 0x00, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(1) RAcc(0)
        0x04, 0x01, 0x00, 0x2c, // DataMove(<-) Local(4) Acc(1)
        0x04, 0x01, 0x00, 0x21, // DataMove(->) Local(4) Vars(1)
        0x00, 0x03, 0x02, 0x22, // DataMove(<-) Local(0) Consts(3) 2(+1)
        0x03, 0x01, 0x00, 0x20, // DataMove(<-) Local(3) Vars(1)
        0x00, 0x02, 0x00, 0x30, // LoadWeights  Local(0) 2(+1)
        0x03, 0x00, 0x00, 0x10, // MatMul       Local(3) Acc(0)
        0x04, 0x00, 0x00, 0x2c, // DataMove(<-) Local(4) Acc(0)
        0x04, 0x00, 0x00, 0x21, // DataMove(->) Local(4) Vars(0)
      ).map(_.toByte)
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile TF XOR for 2x2 array with 256 memories and input batch of 4" in {
    val name = "xor_2x2_memory256_batch4"
    val options = CompilerOptions(
      arch = Tiny2x2Architecure,
      inputBatchSize = 4,
      printProgramFileName = Some(s"${name}.tasm")
    )

    Compiler.compile(
      name,
      s"$Models/xor.pb",
      List("Identity"),
      options
    )

    assert(
      getConstsBytes(name) === Array(
        // sequential_10/dense_23/BiasAdd/ReadVariableOp
        // 0,0
        0x0, 0x0,
        // 0,1
        0xe3, 0xff,
        // sequential_10/dense_23/MatMul/ReadVariableOp
        // 0,0
        0x2e, 0xff,
        // 0,1
        0xe9, 0x0,
        // 1,0
        0xd2, 0x0,
        // 1,1
        0x34, 0xff,
        // sequential_10/dense_24/BiasAdd/ReadVariableOp
        // 0,0
        0x1, 0x0,
        // 0,1
        0x0, 0x0,
        // sequential_10/dense_24/MatMul/ReadVariableOp
        // 0,0
        0x35, 0x1,
        // 0,1
        0x0, 0x0,
        // 1,0
        0x3e, 0x1,
        // 1,1
        0x0, 0x0,
      ).map(_.toByte)
    )

    assert(
      getProgramBytes(name) === Array(
        0x00, 0x00, 0x02, 0x22, // DataMove(<-) Local(0) Consts(0) 2(+1)
        0x03, 0x00, 0x03, 0x20, // DataMove(<-) Local(3) Vars(0) 3(+1)
        0x00, 0x02, 0x00, 0x30, // LoadWeights  Local(0) 2(+1)
        0x03, 0x00, 0x03, 0x10, // MatMul       Local(3) Acc(0) 3(+1)
        0x00, 0x00, 0x09, 0x40, // SIMD         R1=0
        0x04, 0x00, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(4) RAcc(0)
        0x05, 0x01, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(5) RAcc(1)
        0x06, 0x02, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(6) RAcc(2)
        0x07, 0x03, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(7) RAcc(3)
        0x07, 0x04, 0x03, 0x2c, // DataMove(<-) Local(7) Acc(4) 3(+1)
        0x07, 0x04, 0x03, 0x21, // DataMove(->) Local(7) Vars(4) 3(+1)
        0x00, 0x03, 0x02, 0x22, // DataMove(<-) Local(0) Consts(3) 2(+1)
        0x03, 0x04, 0x03, 0x20, // DataMove(<-) Local(3) Vars(4) 3(+1)
        0x00, 0x02, 0x00, 0x30, // LoadWeights  Local(0) 2(+1)
        0x03, 0x00, 0x03, 0x10, // MatMul       Local(3) Acc(0) 3(+1)
        0x07, 0x00, 0x03, 0x2c, // DataMove(<-) Local(7) Acc(0) 3(+1)
        0x07, 0x00, 0x03, 0x21, // DataMove(->) Local(7) Vars(0) 3(+1)
      ).map(_.toByte)
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF XOR for 2x2 array with 256 memories and input batch of 16" in {
    val name = "xor_2x2_memory256_batch16"
    val options = CompilerOptions(
      arch = Tiny2x2Architecure,
      inputBatchSize = 16,
      printProgramFileName = Some(s"${name}.tasm")
    )

    Compiler.compile(
      name,
      s"$Models/xor.pb",
      List("Identity"),
      options
    )

    assert(
      getConstsBytes(name) === Array(
        // sequential_10/dense_23/BiasAdd/ReadVariableOp
        // 0,0
        0x0, 0x0,
        // 0,1
        0xe3, 0xff,
        // sequential_10/dense_23/MatMul/ReadVariableOp
        // 0,0
        0x2e, 0xff,
        // 0,1
        0xe9, 0x0,
        // 1,0
        0xd2, 0x0,
        // 1,1
        0x34, 0xff,
        // sequential_10/dense_24/BiasAdd/ReadVariableOp
        // 0,0
        0x1, 0x0,
        // 0,1
        0x0, 0x0,
        // sequential_10/dense_24/MatMul/ReadVariableOp
        // 0,0
        0x35, 0x1,
        // 0,1
        0x0, 0x0,
        // 1,0
        0x3e, 0x1,
        // 1,1
        0x0, 0x0,
      ).map(_.toByte)
    )

    assert(
      getProgramBytes(name) === Array(
        0x00, 0x00, 0x02, 0x22, // DataMove(<-) Local(0) Consts(0) 2(+1)
        0x03, 0x00, 0x0f, 0x20, // DataMove(<-) Local(3) Vars(0) 15(+1)
        0x00, 0x02, 0x00, 0x30, // LoadWeights  Local(0) 2(+1)
        0x03, 0x00, 0x0f, 0x10, // MatMul       Local(3) Acc(0) 15(+1)
        0x00, 0x00, 0x09, 0x40, // SIMD         R1=0
        0x10, 0x00, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(16) RAcc(0)
        0x11, 0x01, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(17) RAcc(1)
        0x12, 0x02, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(18) RAcc(2)
        0x13, 0x03, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(19) RAcc(3)
        0x14, 0x04, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(20) RAcc(4)
        0x15, 0x05, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(21) RAcc(5)
        0x16, 0x06, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(22) RAcc(6)
        0x17, 0x07, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(23) RAcc(7)
        0x18, 0x08, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(24) RAcc(8)
        0x19, 0x09, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(25) RAcc(9)
        0x1a, 0x0a, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(26) RAcc(10)
        0x1b, 0x0b, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(27) RAcc(11)
        0x1c, 0x0c, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(28) RAcc(12)
        0x1d, 0x0d, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(29) RAcc(13)
        0x1e, 0x0e, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(30) RAcc(14)
        0x1f, 0x0f, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(31) RAcc(15)
        0x13, 0x10, 0x0f, 0x2c, // DataMove(<-) Local(19) Acc(16) 15(+1)
        0x13, 0x10, 0x0f, 0x21, // DataMove(->) Local(19) Vars(16) 15(+1)
        0x00, 0x03, 0x02, 0x22, // DataMove(<-) Local(0) Consts(3) 2(+1)
        0x03, 0x10, 0x0f, 0x20, // DataMove(<-) Local(3) Vars(16) 15(+1)
        0x00, 0x02, 0x00, 0x30, // LoadWeights  Local(0) 2(+1)
        0x03, 0x00, 0x0f, 0x10, // MatMul       Local(3) Acc(0) 15(+1)
        0x13, 0x00, 0x0f, 0x2c, // DataMove(<-) Local(19) Acc(0) 15(+1)
        0x13, 0x00, 0x0f, 0x21, // DataMove(->) Local(19) Vars(0) 15(+1)
      ).map(_.toByte)
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF oversized XOR for 4x4 array with 256 memories" in {
    val name = "xor_4x4_memory256"
    val options = CompilerOptions(
      arch = Tiny4x4Architecure,
      printProgramFileName = Some(s"${name}.tasm")
    )

    Compiler.compile(
      name,
      s"$Models/xor.pb",
      List("Identity"),
      options
    )

    assert(
      getConstsBytes(name) === Array(
        // sequential_10/dense_23/BiasAdd/ReadVariableOp
        // 0,0
        0x0, 0x0,
        // 0,1
        0xe3, 0xff,
        // 0,2
        0x0, 0x0,
        // 0,3
        0x0, 0x0,
        // sequential_10/dense_23/MatMul/ReadVariableOp
        // 0,0
        0x2e, 0xff,
        // 0,1
        0xe9, 0x0,
        // 0,2
        0x0, 0x0,
        // 0,3
        0x0, 0x0,
        // 1,0
        0xd2, 0x0,
        // 1,1
        0x34, 0xff,
        // 1,2
        0x0, 0x0,
        // 1,3
        0x0, 0x0,
        // 2,0
        0x0, 0x0,
        // 2,1
        0x0, 0x0,
        // 2,2
        0x0, 0x0,
        // 2,3
        0x0, 0x0,
        // 3,0
        0x0, 0x0,
        // 3,1
        0x0, 0x0,
        // 3,2
        0x0, 0x0,
        // 3,3
        0x0, 0x0,
        // sequential_10/dense_24/BiasAdd/ReadVariableOp
        // 0,0
        0x1, 0x0,
        // 0,1
        0x0, 0x0,
        // 0,2
        0x0, 0x0,
        // 0,3
        0x0, 0x0,
        // sequential_10/dense_24/MatMul/ReadVariableOp
        // 0,0
        0x35, 0x1,
        // 0,1
        0x0, 0x0,
        // 0,2
        0x0, 0x0,
        // 0,3
        0x0, 0x0,
        // 1,0
        0x3e, 0x1,
        // 1,1
        0x0, 0x0,
        // 1,2
        0x0, 0x0,
        // 1,3
        0x0, 0x0,
        // 2,0
        0x0, 0x0,
        // 2,1
        0x0, 0x0,
        // 2,2
        0x0, 0x0,
        // 2,3
        0x0, 0x0,
        // 3,0
        0x0, 0x0,
        // 3,1
        0x0, 0x0,
        // 3,2
        0x0, 0x0,
        // 3,3
        0x0, 0x0,
      ).map(_.toByte)
    )

    assert(
      getProgramBytes(name) === Array(
        0x00, 0x00, 0x04, 0x22, // DataMove(<-) Local(0) Consts(0) 4(+1)
        0x05, 0x00, 0x00, 0x20, // DataMove(<-) Local(5) Vars(0)
        0x00, 0x04, 0x00, 0x30, // LoadWeights  Local(0) 4(+1)
        0x05, 0x00, 0x00, 0x10, // MatMul       Local(5) Acc(0)
        0x00, 0x00, 0x09, 0x40, // SIMD         R1=0
        0x01, 0x00, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(1) RAcc(0)
        0x06, 0x01, 0x00, 0x2c, // DataMove(<-) Local(6) Acc(1)
        0x06, 0x01, 0x00, 0x21, // DataMove(->) Local(6) Vars(1)
        0x00, 0x05, 0x04, 0x22, // DataMove(<-) Local(0) Consts(5) 4(+1)
        0x05, 0x01, 0x00, 0x20, // DataMove(<-) Local(5) Vars(1)
        0x00, 0x04, 0x00, 0x30, // LoadWeights  Local(0) 4(+1)
        0x05, 0x00, 0x00, 0x10, // MatMul       Local(5) Acc(0)
        0x06, 0x00, 0x00, 0x2c, // DataMove(<-) Local(6) Acc(0)
        0x06, 0x00, 0x00, 0x21, // DataMove(->) Local(6) Vars(0)
      ).map(_.toByte)
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF XOR for 2x2 array with 64K memories" in {
    val name = "xor_2x2_memory64K"
    val options = CompilerOptions(
      arch = Large2x2Architecure,
      printProgramFileName = Some(s"${name}.tasm")
    )

    Compiler.compile(
      name,
      s"$Models/xor.pb",
      List("Identity"),
      options
    )

    assert(
      getConstsBytes(name) === Array(
        // sequential_10/dense_23/BiasAdd/ReadVariableOp
        // 0,0
        0x0, 0x0,
        // 0,1
        0xe3, 0xff,
        // sequential_10/dense_23/MatMul/ReadVariableOp
        // 0,0
        0x2e, 0xff,
        // 0,1
        0xe9, 0x0,
        // 1,0
        0xd2, 0x0,
        // 1,1
        0x34, 0xff,
        // sequential_10/dense_24/BiasAdd/ReadVariableOp
        // 0,0
        0x1, 0x0,
        // 0,1
        0x0, 0x0,
        // sequential_10/dense_24/MatMul/ReadVariableOp
        // 0,0
        0x35, 0x1,
        // 0,1
        0x0, 0x0,
        // 1,0
        0x3e, 0x1,
        // 1,1
        0x0, 0x0,
      ).map(_.toByte)
    )

    assert(
      getProgramBytes(name) === Array(
        0x00, 0x00, 0x00, 0x00, 0x02, 0x00,
        0x22, // DataMove(<-) Local(0) Consts(0) 2(+1)
        0x03, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x20,                                     // DataMove(<-) Local(3) Vars(0)
        0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x30, // LoadWeights  Local(0) 2(+1)
        0x03, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x10,                                     // MatMul       Local(3) Acc(0)
        0x00, 0x00, 0x00, 0x00, 0x09, 0x00, 0x40, // SIMD         R1=0
        0x01, 0x00, 0x00, 0x00, 0x7a, 0x00,
        0x43, // SIMD(RW)     O=Max(I,R1) WAcc(1) RAcc(0)
        0x04, 0x00, 0x01, 0x00, 0x00, 0x00,
        0x2c, // DataMove(<-) Local(4) Acc(1)
        0x04, 0x00, 0x01, 0x00, 0x00, 0x00,
        0x21, // DataMove(->) Local(4) Vars(1)
        0x00, 0x00, 0x03, 0x00, 0x02, 0x00,
        0x22, // DataMove(<-) Local(0) Consts(3) 2(+1)
        0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
        0x20,                                     // DataMove(<-) Local(3) Vars(1)
        0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x30, // LoadWeights  Local(0) 2(+1)
        0x03, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x10, // MatMul       Local(3) Acc(0)
        0x04, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x2c, // DataMove(<-) Local(4) Acc(0)
        0x04, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x21, // DataMove(->) Local(4) Vars(0)
      ).map(_.toByte)
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF XOR4 for 4x4 array with 256 memories" in {
    val name = "xor4_4x4_memory256"
    val options = CompilerOptions(
      arch = Tiny4x4Architecure,
      printProgramFileName = Some(s"${name}.tasm")
    )

    Compiler.compile(
      name,
      s"$Models/xor4.pb",
      List("Identity"),
      options
    )

    assert(
      getConstsBytes(name) === Array(
        // sequential_10/dense_23/BiasAdd/ReadVariableOp
        // 0,0
        0xfe, 0xfe,
        // 0,1
        0xe, 0x0,
        // 0,2
        0x3f, 0x0,
        // 0,3
        0x0, 0x0,
        // sequential_10/dense_23/MatMul/ReadVariableOp
        // 0,0
        0x2, 0x1,
        // 0,1
        0xfc, 0x0,
        // 0,2
        0x6a, 0x0,
        // 0,3
        0xd8, 0xff,
        // 1,0
        0x2, 0x1,
        // 1,1
        0xa8, 0x0,
        // 1,2
        0xc1, 0xff,
        // 1,3
        0x66, 0xff,
        // 2,0
        0x0, 0x0,
        // 2,1
        0x0, 0x0,
        // 2,2
        0x0, 0x0,
        // 2,3
        0x0, 0x0,
        // 3,0
        0x0, 0x0,
        // 3,1
        0x0, 0x0,
        // 3,2
        0x0, 0x0,
        // 3,3
        0x0, 0x0,
        // sequential_10/dense_24/BiasAdd/ReadVariableOp
        // 0,0
        0x16, 0x0,
        // 0,1
        0x0, 0x0,
        // 0,2
        0x0, 0x0,
        // 0,3
        0x0, 0x0,
        // sequential_10/dense_24/MatMul/ReadVariableOp
        // 0,0
        0x6, 0xfe,
        // 0,1
        0x0, 0x0,
        // 0,2
        0x0, 0x0,
        // 0,3
        0x0, 0x0,
        // 1,0
        0x48, 0x1,
        // 1,1
        0x0, 0x0,
        // 1,2
        0x0, 0x0,
        // 1,3
        0x0, 0x0,
        // 2,0
        0x5d, 0xff,
        // 2,1
        0x0, 0x0,
        // 2,2
        0x0, 0x0,
        // 2,3
        0x0, 0x0,
        // 3,0
        0x1f, 0x0,
        // 3,1
        0x0, 0x0,
        // 3,2
        0x0, 0x0,
        // 3,3
        0x0, 0x0,
      ).map(_.toByte)
    )

    assert(
      getProgramBytes(name) === Array(
        0x00, 0x00, 0x04, 0x22, // DataMove(<-) Local(0) Consts(0) 4(+1)
        0x05, 0x00, 0x00, 0x20, // DataMove(<-) Local(5) Vars(0)
        0x00, 0x04, 0x00, 0x30, // LoadWeights  Local(0) 4(+1)
        0x05, 0x00, 0x00, 0x10, // MatMul       Local(5) Acc(0)
        0x00, 0x00, 0x09, 0x40, // SIMD         R1=0
        0x01, 0x00, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(1) RAcc(0)
        0x06, 0x01, 0x00, 0x2c, // DataMove(<-) Local(6) Acc(1)
        0x06, 0x01, 0x00, 0x21, // DataMove(->) Local(6) Vars(1)
        0x00, 0x05, 0x04, 0x22, // DataMove(<-) Local(0) Consts(5) 4(+1)
        0x05, 0x01, 0x00, 0x20, // DataMove(<-) Local(5) Vars(1)
        0x00, 0x04, 0x00, 0x30, // LoadWeights  Local(0) 4(+1)
        0x05, 0x00, 0x00, 0x10, // MatMul       Local(5) Acc(0)
        0x06, 0x00, 0x00, 0x2c, // DataMove(<-) Local(6) Acc(0)
        0x06, 0x00, 0x00, 0x21, // DataMove(->) Local(6) Vars(0)
      ).map(_.toByte)
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF tiled XOR4 for 2x2 array with 128 memories and 2 strides" in {
    val name = "xor4_2x2_memory128_stride2"
    val options = CompilerOptions(
      arch = Large2x2WithStridesArchitecure,
      printProgramFileName = Some(s"${name}.tasm")
    )

    Compiler.compile(
      name,
      s"$Models/xor4.pb",
      List("Identity"),
      options
    )

    assert(
      getConstsBytes(name) === Array(
        // sequential_10/dense_23/BiasAdd/ReadVariableOp
        // 0,0
        0xfe, 0xfe,
        // 0,1
        0xe, 0x0,
        // 0,2
        0x3f, 0x0,
        // 0,3
        0x0, 0x0,
        // sequential_10/dense_23/MatMul/ReadVariableOp
        // 0,0
        0x2, 0x1,
        // 0,1
        0xfc, 0x0,
        // 0,2
        0x6a, 0x0,
        // 0,3
        0xd8, 0xff,
        // 1,0
        0x2, 0x1,
        // 1,1
        0xa8, 0x0,
        // 1,2
        0xc1, 0xff,
        // 1,3
        0x66, 0xff,
        // sequential_10/dense_24/BiasAdd/ReadVariableOp
        // 0,0
        0x16, 0x0,
        // 0,1
        0x0, 0x0,
        // sequential_10/dense_24/MatMul/ReadVariableOp
        // 0,0
        0x6, 0xfe,
        // 0,1
        0x0, 0x0,
        // 0,2
        0x48, 0x1,
        // 1,1
        0x0, 0x0,
        // 2,0
        0x5d, 0xff,
        // 2,1
        0x0, 0x0,
        // 3,0
        0x1f, 0x0,
        // 3,1
        0x0, 0x0,
      ).map(_.toByte)
    )

    assert(
      getProgramBytes(name) === Array(
        0x00, 0x80, 0x02, 0x22, // DataMove(<-) Local(0) Consts(0)@2^1 2(+1)
        0x03, 0x81, 0x02, 0x22, // DataMove(<-) Local(3) Consts(1)@2^1 2(+1)
        0x06, 0x00, 0x00, 0x20, // DataMove(<-) Local(6) Vars(0)
        0x00, 0x02, 0x00, 0x30, // LoadWeights  Local(0) 2(+1)
        0x06, 0x00, 0x00, 0x10, // MatMul       Local(6) Acc(0)
        0x03, 0x02, 0x00, 0x30, // LoadWeights  Local(3) 2(+1)
        0x06, 0x01, 0x00, 0x10, // MatMul       Local(6) Acc(1)
        0x00, 0x00, 0x09, 0x40, // SIMD         R1=0
        0x02, 0x00, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(2) RAcc(0)
        0x03, 0x01, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(3) RAcc(1)
        0x07, 0x02, 0x01, 0x2c, // DataMove(<-) Local(7) Acc(2) 1(+1)
        0x07, 0x01, 0x01, 0x21, // DataMove(->) Local(7) Vars(1) 1(+1)
        0x00, 0x06, 0x04, 0x22, // DataMove(<-) Local(0) Consts(6) 4(+1)
        0x05, 0x01, 0x01, 0x20, // DataMove(<-) Local(5) Vars(1) 1(+1)
        0x01, 0x01, 0x00, 0x30, // LoadWeights  Local(1) 1(+1)
        0x00, 0x00, 0x00, 0x31, // LoadWeights  Zeroes
        0x05, 0x00, 0x00, 0x10, // MatMul       Local(5) Acc(0)
        0x03, 0x01, 0x00, 0x30, // LoadWeights  Local(3) 1(+1)
        0x00, 0x00, 0x00, 0x30, // LoadWeights  Local(0)
        0x06, 0x00, 0x00, 0x11, // MatMul(Acc)  Local(6) Acc(0)
        0x07, 0x00, 0x00, 0x2c, // DataMove(<-) Local(7) Acc(0)
        0x07, 0x00, 0x00, 0x21, // DataMove(->) Local(7) Vars(0)
      ).map(_.toByte)
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF tiled XOR4 for 2x2 array with 128 memories, 2 strides and input batch of 4" in {
    val name         = "xor4_2x2_memory128_stride2_batch4"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = Large2x2WithStridesArchitecure,
      inputBatchSize = 4,
      printSummary = true,
      printProgramFileName = Some(s"${name}.tasm"),
      printProgramWithComments = true,
      printGraphFileName = Some(s"${name}.dot"),
      tracepointConditions = List(
        TracepointCondition(MemoryTag.Vars, "x"),
        TracepointCondition(MemoryTag.Local, "x"),
        TracepointCondition(MemoryTag.Accumulators, "x"),
        TracepointCondition(MemoryTag.Vars, "sequential_3/dense_6/BiasAdd"),
        TracepointCondition(MemoryTag.Local, "sequential_3/dense_6/BiasAdd"),
        TracepointCondition(
          MemoryTag.Accumulators,
          "sequential_3/dense_6/BiasAdd"
        ),
        TracepointCondition(MemoryTag.Vars, "sequential_3/dense_6/Relu"),
        TracepointCondition(MemoryTag.Local, "sequential_3/dense_6/Relu"),
        TracepointCondition(
          MemoryTag.Accumulators,
          "sequential_3/dense_6/Relu"
        ),
        TracepointCondition(MemoryTag.Vars, "sequential_3/dense_7/BiasAdd"),
        TracepointCondition(MemoryTag.Local, "sequential_3/dense_7/BiasAdd"),
        TracepointCondition(
          MemoryTag.Accumulators,
          "sequential_3/dense_7/BiasAdd"
        ),
        TracepointCondition(
          MemoryTag.Consts,
          "sequential_3/dense_6/MatMul/ReadVariableOp"
        ),
        TracepointCondition(
          MemoryTag.Consts,
          "sequential_3/dense_6/BiasAdd/ReadVariableOp"
        ),
        TracepointCondition(
          MemoryTag.Consts,
          "sequential_3/dense_7/MatMul/ReadVariableOp"
        ),
        TracepointCondition(
          MemoryTag.Consts,
          "sequential_3/dense_7/BiasAdd/ReadVariableOp"
        )
      )
    )

    Compiler.compile(
      name,
      s"$Models/xor4.pb",
      List("Identity"),
      options,
      traceContext
    )

    assert(
      getConstsBytes(name) === Array(
        // sequential_10/dense_23/BiasAdd/ReadVariableOp
        // 0,0
        0xfe, 0xfe,
        // 0,1
        0xe, 0x0,
        // 0,2
        0x3f, 0x0,
        // 0,3
        0x0, 0x0,
        // sequential_10/dense_23/MatMul/ReadVariableOp
        // 0,0
        0x2, 0x1,
        // 0,1
        0xfc, 0x0,
        // 0,2
        0x6a, 0x0,
        // 0,3
        0xd8, 0xff,
        // 1,0
        0x2, 0x1,
        // 1,1
        0xa8, 0x0,
        // 1,2
        0xc1, 0xff,
        // 1,3
        0x66, 0xff,
        // sequential_10/dense_24/BiasAdd/ReadVariableOp
        // 0,0
        0x16, 0x0,
        // 0,1
        0x0, 0x0,
        // sequential_10/dense_24/MatMul/ReadVariableOp
        // 0,0
        0x6, 0xfe,
        // 0,1
        0x0, 0x0,
        // 0,2
        0x48, 0x1,
        // 1,1
        0x0, 0x0,
        // 2,0
        0x5d, 0xff,
        // 2,1
        0x0, 0x0,
        // 3,0
        0x1f, 0x0,
        // 3,1
        0x0, 0x0,
      ).map(_.toByte)
    )

    assert(
      getProgramBytes(name) === Array(
        0x00, 0x80, 0x02, 0x22, // DataMove(<-) Local(0) Consts(0)@2^1 2(+1)
        0x03, 0x81, 0x02, 0x22, // DataMove(<-) Local(3) Consts(1)@2^1 2(+1)
        0x06, 0x00, 0x03, 0x20, // DataMove(<-) Local(6) Vars(0) 3(+1)
        0x00, 0x02, 0x00, 0x30, // LoadWeights  Local(0) 2(+1)
        0x06, 0x80, 0x03, 0x10, // MatMul       Local(6) Acc(0)@2^1 3(+1)
        0x03, 0x02, 0x00, 0x30, // LoadWeights  Local(3) 2(+1)
        0x06, 0x81, 0x03, 0x10, // MatMul       Local(6) Acc(1)@2^1 3(+1)
        0x00, 0x00, 0x09, 0x40, // SIMD         R1=0
        0x08, 0x00, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(8) RAcc(0)
        0x09, 0x01, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(9) RAcc(1)
        0x0a, 0x02, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(10) RAcc(2)
        0x0b, 0x03, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(11) RAcc(3)
        0x0c, 0x04, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(12) RAcc(4)
        0x0d, 0x05, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(13) RAcc(5)
        0x0e, 0x06, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(14) RAcc(6)
        0x0f, 0x07, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(15) RAcc(7)
        0x0a, 0x08, 0x07, 0x2c, // DataMove(<-) Local(10) Acc(8) 7(+1)
        0x0a, 0x04, 0x07, 0x21, // DataMove(->) Local(10) Vars(4) 7(+1)
        0x00, 0x06, 0x04, 0x22, // DataMove(<-) Local(0) Consts(6) 4(+1)
        0x05, 0x04, 0x07, 0x20, // DataMove(<-) Local(5) Vars(4) 7(+1)
        0x01, 0x01, 0x00, 0x30, // LoadWeights  Local(1) 1(+1)
        0x00, 0x00, 0x00, 0x31, // LoadWeights  Zeroes
        0x85, 0x00, 0x03, 0x10, // MatMul       Local(5)@2^1 Acc(0) 3(+1)
        0x03, 0x01, 0x00, 0x30, // LoadWeights  Local(3) 1(+1)
        0x00, 0x00, 0x00, 0x30, // LoadWeights  Local(0)
        0x86, 0x00, 0x03, 0x11, // MatMul(Acc)  Local(6)@2^1 Acc(0) 3(+1)
        0x0d, 0x00, 0x03, 0x2c, // DataMove(<-) Local(13) Acc(0) 3(+1)
        0x0d, 0x00, 0x03, 0x21, // DataMove(->) Local(13) Vars(0) 3(+1)
      ).map(_.toByte)
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile TF tiled XOR4 for 2x2 array with 128 memories, 2 strides and input batch of 16" in {
    val name = "xor4_2x2_memory128_stride2_batch16"
    val options = CompilerOptions(
      arch = Large2x2WithStridesArchitecure,
      inputBatchSize = 16,
      printProgramFileName = Some(s"${name}.tasm")
    )

    Compiler.compile(
      name,
      s"$Models/xor4.pb",
      List("Identity"),
      options
    )

    assert(
      getConstsBytes(name) === Array(
        // sequential_10/dense_23/BiasAdd/ReadVariableOp
        // 0,0
        0xfe, 0xfe,
        // 0,1
        0xe, 0x0,
        // 0,2
        0x3f, 0x0,
        // 0,3
        0x0, 0x0,
        // sequential_10/dense_23/MatMul/ReadVariableOp
        // 0,0
        0x2, 0x1,
        // 0,1
        0xfc, 0x0,
        // 0,2
        0x6a, 0x0,
        // 0,3
        0xd8, 0xff,
        // 1,0
        0x2, 0x1,
        // 1,1
        0xa8, 0x0,
        // 1,2
        0xc1, 0xff,
        // 1,3
        0x66, 0xff,
        // sequential_10/dense_24/BiasAdd/ReadVariableOp
        // 0,0
        0x16, 0x0,
        // 0,1
        0x0, 0x0,
        // sequential_10/dense_24/MatMul/ReadVariableOp
        // 0,0
        0x6, 0xfe,
        // 0,1
        0x0, 0x0,
        // 0,2
        0x48, 0x1,
        // 1,1
        0x0, 0x0,
        // 2,0
        0x5d, 0xff,
        // 2,1
        0x0, 0x0,
        // 3,0
        0x1f, 0x0,
        // 3,1
        0x0, 0x0,
      ).map(_.toByte)
    )

    assert(
      getProgramBytes(name) === Array(
        0x00, 0x80, 0x02, 0x22, // DataMove(<-) Local(0) Consts(0)@2^1 2(+1)
        0x03, 0x81, 0x02, 0x22, // DataMove(<-) Local(3) Consts(1)@2^1 2(+1)
        0x06, 0x00, 0x0f, 0x20, // DataMove(<-) Local(6) Vars(0) 15(+1)
        0x00, 0x02, 0x00, 0x30, // LoadWeights  Local(0) 2(+1)
        0x06, 0x80, 0x0f, 0x10, // MatMul       Local(6) Acc(0)@2^1 15(+1)
        0x03, 0x02, 0x00, 0x30, // LoadWeights  Local(3) 2(+1)
        0x06, 0x81, 0x0f, 0x10, // MatMul       Local(6) Acc(1)@2^1 15(+1)
        0x00, 0x00, 0x09, 0x40, // SIMD         R1=0
        0x20, 0x00, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(32) RAcc(0)
        0x21, 0x01, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(33) RAcc(1)
        0x22, 0x02, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(34) RAcc(2)
        0x23, 0x03, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(35) RAcc(3)
        0x24, 0x04, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(36) RAcc(4)
        0x25, 0x05, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(37) RAcc(5)
        0x26, 0x06, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(38) RAcc(6)
        0x27, 0x07, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(39) RAcc(7)
        0x28, 0x08, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(40) RAcc(8)
        0x29, 0x09, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(41) RAcc(9)
        0x2a, 0x0a, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(42) RAcc(10)
        0x2b, 0x0b, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(43) RAcc(11)
        0x2c, 0x0c, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(44) RAcc(12)
        0x2d, 0x0d, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(45) RAcc(13)
        0x2e, 0x0e, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(46) RAcc(14)
        0x2f, 0x0f, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(47) RAcc(15)
        0x30, 0x10, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(48) RAcc(16)
        0x31, 0x11, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(49) RAcc(17)
        0x32, 0x12, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(50) RAcc(18)
        0x33, 0x13, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(51) RAcc(19)
        0x34, 0x14, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(52) RAcc(20)
        0x35, 0x15, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(53) RAcc(21)
        0x36, 0x16, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(54) RAcc(22)
        0x37, 0x17, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(55) RAcc(23)
        0x38, 0x18, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(56) RAcc(24)
        0x39, 0x19, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(57) RAcc(25)
        0x3a, 0x1a, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(58) RAcc(26)
        0x3b, 0x1b, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(59) RAcc(27)
        0x3c, 0x1c, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(60) RAcc(28)
        0x3d, 0x1d, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(61) RAcc(29)
        0x3e, 0x1e, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(62) RAcc(30)
        0x3f, 0x1f, 0x7a, 0x43, // SIMD(RW)     O=Max(I,R1) WAcc(63) RAcc(31)
        0x16, 0x20, 0x1f, 0x2c, // DataMove(<-) Local(22) Acc(32) 31(+1)
        0x16, 0x10, 0x1f, 0x21, // DataMove(->) Local(22) Vars(16) 31(+1)
        0x00, 0x06, 0x04, 0x22, // DataMove(<-) Local(0) Consts(6) 4(+1)
        0x05, 0x10, 0x1f, 0x20, // DataMove(<-) Local(5) Vars(16) 31(+1)
        0x01, 0x01, 0x00, 0x30, // LoadWeights  Local(1) 1(+1)
        0x00, 0x00, 0x00, 0x31, // LoadWeights  Zeroes
        0x85, 0x00, 0x0f, 0x10, // MatMul       Local(5)@2^1 Acc(0) 15(+1)
        0x03, 0x01, 0x00, 0x30, // LoadWeights  Local(3) 1(+1)
        0x00, 0x00, 0x00, 0x30, // LoadWeights  Local(0)
        0x86, 0x00, 0x0f, 0x11, // MatMul(Acc)  Local(6)@2^1 Acc(0) 15(+1)
        0x25, 0x00, 0x0f, 0x2c, // DataMove(<-) Local(37) Acc(0) 15(+1)
        0x25, 0x00, 0x0f, 0x21, // DataMove(->) Local(37) Vars(0) 15(+1)
      ).map(_.toByte)
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  val MNIST784x784Architecture = Architecture.mkWithDefaults(
    arraySize = 784,
    dram0Depth = Kibi * 64,
    dram1Depth = Kibi * 64,
    accumulatorDepth = Kibi * 64,
    localDepth = Kibi * 64
  )

  val MNIST196x196Architecture = Architecture.mkWithDefaults(
    arraySize = 196,
    dram0Depth = Kibi * 64,
    dram1Depth = Kibi * 64,
    accumulatorDepth = Kibi * 64,
    localDepth = Kibi * 64
  )

  val MNIST128x128Architecture = Architecture.mkWithDefaults(
    arraySize = 128,
    dram0Depth = Kibi * 64,
    dram1Depth = Kibi * 64,
    accumulatorDepth = Kibi * 64,
    localDepth = Kibi * 64
  )
  val MNIST64x64Architecture = Architecture.mkWithDefaults(
    arraySize = 64,
    dram0Depth = Kibi * 64,
    dram1Depth = Kibi * 64,
    accumulatorDepth = Kibi * 64,
    localDepth = Kibi * 64
  )
  val MNIST16x16Architecture = Architecture.mkWithDefaults(
    arraySize = 16,
    dram0Depth = Kibi * 64,
    dram1Depth = Kibi * 64,
    accumulatorDepth = Kibi * 64,
    localDepth = Kibi * 64
  )
  val MNIST16x16With128KArchitecture = Architecture.mkWithDefaults(
    arraySize = 16,
    dram0Depth = Kibi * 128,
    dram1Depth = Kibi * 128,
    accumulatorDepth = Kibi * 128,
    localDepth = Kibi * 128
  )
  val MNIST16x16With256Acc4KLocArchitecture = Architecture.mkWithDefaults(
    arraySize = 16,
    dram0Depth = Kibi * 64,
    dram1Depth = Kibi * 64,
    accumulatorDepth = 256,
    localDepth = 4096,
    stride0Depth = 8,
    stride1Depth = 8
  )

  it should "Compile TF MLP MNIST for 784x784 array with 64K memories" in {
    val name = "mlp_mnist_784x784"
    val options = CompilerOptions(
      arch = MNIST784x784Architecture,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/mlp_mnist.pb",
      List("Identity"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF MLP MNIST for 784x784 array with 64K memories and input batch of 4" in {
    val name = "mlp_mnist_784x784_batch4"
    val options = CompilerOptions(
      arch = MNIST784x784Architecture,
      inputBatchSize = 4,
      printSummary = true,
    )

    Compiler.compile(
      name,
      s"$Models/mlp_mnist.pb",
      List("Identity"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF tiled MLP MNIST for 196x196 array with 64K memories" in {
    val name = "mlp_mnist_196x196"
    val options = CompilerOptions(
      arch = MNIST196x196Architecture,
      printSummary = true,
    )

    Compiler.compile(
      name,
      s"$Models/mlp_mnist.pb",
      List("Identity"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF tiled MLP MNIST for 196x196 array with 64K memories and input batch of 10" in {
    val name = "mlp_mnist_196x196_batch10"
    val options = CompilerOptions(
      arch = MNIST196x196Architecture,
      inputBatchSize = 10,
      printSummary = true,
    )

    Compiler.compile(
      name,
      s"$Models/mlp_mnist.pb",
      List("Identity"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF tiled MLP MNIST for 128x128 array with 64K memories" in {
    val name = "mlp_mnist_128x128"
    val options = CompilerOptions(
      arch = MNIST128x128Architecture,
      printSummary = true,
    )

    Compiler.compile(
      name,
      s"$Models/mlp_mnist.pb",
      List("Identity"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF tiled MLP MNIST for 128x128 array with 64K memories and input batch of 10" in {
    val name = "mlp_mnist_128x128_batch10"
    val options = CompilerOptions(
      arch = MNIST128x128Architecture,
      inputBatchSize = 10,
      printSummary = true,
      printGraphFileName = Some(s"${name}.dot")
    )

    Compiler.compile(
      name,
      s"$Models/mlp_mnist.pb",
      List("Identity"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  val Conv2DTiny2x2Architecure = Architecture.mkWithDefaults(
    arraySize = 2,
    dram1Depth = 256,
    dram0Depth = 256,
    accumulatorDepth = 256,
    localDepth = 256,
  )
  val Conv2DTiny4x4Architecure = Architecture.mkWithDefaults(
    arraySize = 4,
    dram1Depth = 256,
    dram0Depth = 256,
    accumulatorDepth = 256,
    localDepth = 256,
  )
  val Conv2DTiny8x8Architecure = Architecture.mkWithDefaults(
    arraySize = 8,
    dram1Depth = 256,
    dram0Depth = 256,
    accumulatorDepth = 256,
    localDepth = 256,
  )

  it should "Compile TF Conv2D (VALID padding) 3x3x4 image with 2x2x4x4 kernel" in {
    val name = "conv2d_4x4_valid"
    val options = CompilerOptions(
      arch = Conv2DTiny4x4Architecure,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/conv2d_4x4_valid.pb",
      List("Identity_1"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF Conv2D (VALID padding, 2x2 strides) 3x3x4 image with 2x2x4x4 kernel" in {
    val name    = "conv2d_4x4_valid_stride_2"
    val options = CompilerOptions(arch = Conv2DTiny4x4Architecure)

    Compiler.compile(
      name,
      s"$Models/conv2d_4x4_valid_stride_2.pb",
      List("Identity_1"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF Conv2D (SAME padding) 3x3x4 image with 2x2x4x4 kernel" in {
    val name    = "conv2d_4x4_same"
    val options = CompilerOptions(arch = Conv2DTiny4x4Architecure)

    Compiler.compile(
      name,
      s"$Models/conv2d_4x4_same.pb",
      List("Identity_1"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF Conv2D (SAME padding, 2x2 strides) 3x3x4 image with 2x2x4x4 kernel" in {
    val name    = "conv2d_4x4_same_stride_2"
    val options = CompilerOptions(arch = Conv2DTiny4x4Architecure)

    Compiler.compile(
      name,
      s"$Models/conv2d_4x4_same_stride_2.pb",
      List("Identity_1"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF tiled Conv2D (VALID padding) 3x3x4 image with 2x2x4x4 kernel" in {
    val name    = "conv2d_4x4_valid_tiled"
    val options = CompilerOptions(arch = Conv2DTiny2x2Architecure)

    Compiler.compile(
      name,
      s"$Models/conv2d_4x4_valid.pb",
      List("Identity_1"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF tiled Conv2D (SAME padding) 3x3x4 image with 2x2x4x4 kernel" in {
    val name    = "conv2d_4x4_same_tiled"
    val options = CompilerOptions(arch = Conv2DTiny2x2Architecure)

    Compiler.compile(
      name,
      s"$Models/conv2d_4x4_same.pb",
      List("Identity_1"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF oversized Conv2D (VALID padding) 3x3x4 image with 2x2x4x4 kernel" in {
    val name    = "conv2d_4x4_valid_oversized"
    val options = CompilerOptions(arch = Conv2DTiny8x8Architecure)

    Compiler.compile(
      name,
      s"$Models/conv2d_4x4_valid.pb",
      List("Identity_1"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF oversized Conv2D (SAME padding) 3x3x4 image with 2x2x4x4 kernel" in {
    val name    = "conv2d_4x4_same_oversized"
    val options = CompilerOptions(arch = Conv2DTiny8x8Architecure)

    Compiler.compile(
      name,
      s"$Models/conv2d_4x4_same.pb",
      List("Identity_1"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF Conv2D (SAME padding) 3x3x4 image with 2x2x4x4 kernel, Relu, MaxPool (VALID padding)" in {
    val name = "conv2d_4x4_same_relu_2x2_maxpool_valid_stride_2"
    val options = CompilerOptions(
      arch = Conv2DTiny4x4Architecure,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/conv2d_4x4_same_relu_2x2_maxpool_valid_stride_2.pb",
      List("Identity_1"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF Conv2D (SAME padding) 3x3x4 image with 2x2x4x4 kernel, Relu, MaxPool (VALID padding, 1x1 stride)" in {
    val name = "conv2d_4x4_same_relu_2x2_maxpool_valid_stride_1"
    val options = CompilerOptions(
      arch = Conv2DTiny4x4Architecure,
    )

    Compiler.compile(
      name,
      s"$Models/conv2d_4x4_same_relu_2x2_maxpool_valid_stride_1.pb",
      List("Identity_1"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "print the compiled program for large" taggedAs (Slow) in {
    val name = "conv2d_3_224_64_128"
    val options = CompilerOptions(
      arch = Architecture.mkWithDefaults(
        arraySize = 256,
        dram1Depth = Kibi * 10,
        dram0Depth = Mebi,
        accumulatorDepth = 512,
        localDepth = Kibi * 4
      ),
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/conv2d_3_224_64_128.pb",
      List("Identity"),
      options
    )
  }

  val MaxPoolArchitecure = Architecture.mkWithDefaults(
    arraySize = 8,
    dram1Depth = Kibi * 64,
    dram0Depth = Kibi * 64,
    accumulatorDepth = Kibi * 64,
    localDepth = Kibi * 64,
  )

  it should "Compile TF MaxPool" in {
    val name    = "maxpool_2_22_5"
    val options = CompilerOptions(arch = MaxPoolArchitecure)

    Compiler.compile(
      name,
      s"$Models/maxpool_2_22_5.pb",
      List("Identity"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF CNN MNIST for 64x64 array with 64K memories" in {
    val name = "cnn_mnist_64x64"
    val options = CompilerOptions(
      arch = MNIST64x64Architecture,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/cnn_mnist.pb",
      List("Identity"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF CNN MNIST for 64x64 array with 64K memories and input batch of 10" in {
    val name = "cnn_mnist_64x64_batch10"
    val options = CompilerOptions(
      arch = MNIST64x64Architecture,
      inputBatchSize = 10,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/cnn_mnist.pb",
      List("Identity"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF tiled CNN MNIST for 16x16 array with 64K memories" in {
    val name = "cnn_mnist_16x16"
    val options = CompilerOptions(
      arch = MNIST16x16Architecture,
      printSummary = true,
    )

    Compiler.compile(
      name,
      s"$Models/cnn_mnist.pb",
      List("Identity"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF tiled CNN MNIST for 16x16 array with 256/4K accumulators/local memories" in {
    val name = "cnn_mnist_16x16_acc256_loc4k"
    val options = CompilerOptions(
      arch = MNIST16x16With256Acc4KLocArchitecture,
      printSummary = true,
    )

    Compiler.compile(
      name,
      s"$Models/cnn_mnist.pb",
      List("Identity"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF tiled CNN MNIST for 16x16 array with 128K memories and input batch of 10" in {
    val name = "cnn_mnist_16x16_memory128k_batch10"
    val options = CompilerOptions(
      arch = MNIST16x16With128KArchitecture,
      inputBatchSize = 10,
      printSummary = true,
      printGraphFileName = Some(s"${name}.dot")
    )

    Compiler.compile(
      name,
      s"$Models/cnn_mnist.pb",
      List("Identity"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  it should "Compile TF tiled CNN MNIST for 16x16 array with 256/4K accumulators/local memories and input batch of 10" in {
    val name = "cnn_mnist_16x16_acc256_loc4k_batch10"
    val options = CompilerOptions(
      arch = MNIST16x16With256Acc4KLocArchitecture,
      inputBatchSize = 10,
      printSummary = true,
    )

    Compiler.compile(
      name,
      s"$Models/cnn_mnist.pb",
      List("Identity"),
      options
    )

    GoldenProcessorHelper.test(name, inputBatchSize = options.inputBatchSize)
  }

  val ResNetFloat32Architecture = Architecture.mkWithDefaults(
    dataType = ArchitectureDataType.FLOAT32,
    arraySize = 8,
    accumulatorDepth = Kibi * 2,
    localDepth = Kibi * 8,
    stride0Depth = 8,
    stride1Depth = 8,
  )

  val ResNetFp16bp8Architecture = Architecture.mkWithDefaults(
    dataType = ArchitectureDataType.FP16BP8,
    arraySize = 8,
    accumulatorDepth = Kibi * 2,
    localDepth = Kibi * 8,
    stride0Depth = 8,
    stride1Depth = 8,
  )

  it should "Compile TF float ResNet20V2 (CIFAR)" taggedAs (Slow) in {
    val name         = "resnet20v2_cifar_8x8_float"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = ResNetFloat32Architecture,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/resnet20v2_cifar.pb",
      List("Identity"),
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile TF fixed16bp8 ResNet20V2 (CIFAR)" in {
    val name         = "resnet20v2_cifar_8x8_fixed16bp8"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = ResNetFp16bp8Architecture,
      printSummary = true,
      printLayersSummary = true,
      collectBackendStats = true,
      printGraphFileName = Some(s"${name}.dot"),
      //printProgramFileName = Some(s"${name}.tasm"),
      tracepointConditions = List(
        TracepointCondition(MemoryTag.Vars, "model/dense/Softmax")
      )
    )

    Compiler.compile(
      name,
      s"$Models/resnet20v2_cifar.pb",
      List("Identity"),
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile TF float ResNet20V2 (CIFAR) and input batch of 10" taggedAs (Slow) in {
    val name         = "resnet20v2_cifar_8x8_batch10_float"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = ResNetFloat32Architecture,
      inputBatchSize = 10,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/resnet20v2_cifar.pb",
      List("Identity"),
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile TF fixed16bp8 ResNet20V2 (CIFAR) and input batch of 10" in {
    val name         = "resnet20v2_cifar_8x8_batch10_fixed16bp8"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = ResNetFp16bp8Architecture,
      inputBatchSize = 10,
      printSummary = true,
      printLayersSummary = true,
      collectBackendStats = true,
      printGraphFileName = Some(s"${name}.dot"),
      //printProgramFileName = Some(s"${name}.tasm"),
      tracepointConditions = List(
        TracepointCondition(MemoryTag.Vars, "model/dense/Softmax")
      )
    )

    Compiler.compile(
      name,
      s"$Models/resnet20v2_cifar.pb",
      List("Identity"),
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  val YoloTinyFloat32Architecture = Architecture.mkWithDefaults(
    dataType = ArchitectureDataType.FLOAT32,
    arraySize = 8,
    accumulatorDepth = Kibi * 2,
    localDepth = Kibi * 8,
    stride0Depth = 8,
    stride1Depth = 8,
  )

  val YoloTinyFp32bp16Architecture = Architecture.mkWithDefaults(
    dataType = ArchitectureDataType.FP32BP16,
    arraySize = 8,
    accumulatorDepth = Kibi * 2,
    localDepth = Kibi * 8,
    stride0Depth = 8,
    stride1Depth = 8,
  )

  val YoloTinyFp18bp10Architecture = Architecture.mkWithDefaults(
    dataType = ArchitectureDataType.FP18BP10,
    arraySize = 8,
    accumulatorDepth = Kibi * 2,
    localDepth = Kibi * 8,
    stride0Depth = 8,
    stride1Depth = 8,
  )

  val YoloTinyFp16bp8Architecture = Architecture.mkWithDefaults(
    dataType = ArchitectureDataType.FP16BP8,
    arraySize = 8,
    accumulatorDepth = Kibi * 2,
    localDepth = Kibi * 8,
    stride0Depth = 8,
    stride1Depth = 8,
  )

  it should "Compile TF float YoloV4-tiny" taggedAs (Slow) in {
    val name         = "yolov4_tiny_192_8x8_float"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = YoloTinyFloat32Architecture,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/yolov4_tiny_192.pb",
      TinyYolo.GoldenOutputFileNames.keys.toList,
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile TF fixed32bp16 YoloV4-tiny" taggedAs (Slow) in {
    val name         = "yolov4_tiny_192_8x8_fixed32bp16"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = YoloTinyFp32bp16Architecture,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/yolov4_tiny_192.pb",
      TinyYolo.GoldenOutputFileNames.keys.toList,
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile TF fixed18bp10 YoloV4-tiny" taggedAs (Slow) in {
    val name         = "yolov4_tiny_192_8x8_fixed18bp10"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = YoloTinyFp18bp10Architecture,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/yolov4_tiny_192.pb",
      TinyYolo.GoldenOutputFileNames.keys.toList,
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile TF fixed16bp8 YoloV4-tiny" in {
    val name         = "yolov4_tiny_192_8x8_fixed16bp8"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = YoloTinyFp16bp8Architecture,
      printSummary = true,
      printLayersSummary = true,
      collectBackendStats = true,
      printGraphFileName = Some(s"${name}.dot")
    )

    Compiler.compile(
      name,
      s"$Models/yolov4_tiny_192.pb",
      TinyYolo.GoldenOutputFileNames.keys.toList,
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  val ResNet50Float32Architecture = Architecture.mkWithDefaults(
    dataType = ArchitectureDataType.FLOAT32,
    arraySize = 16,
    dram0Depth = Mebi * 4,
    dram1Depth = Mebi * 4,
    accumulatorDepth = Kibi * 2,
    localDepth = Kibi * 8,
    stride0Depth = 8,
    stride1Depth = 8,
  )

  val ResNet50Fp16bp8Architecture = Architecture.mkWithDefaults(
    dataType = ArchitectureDataType.FP16BP8,
    arraySize = 16,
    dram0Depth = Mebi * 4,
    dram1Depth = Mebi * 4,
    accumulatorDepth = Kibi * 2,
    localDepth = Kibi * 8,
    stride0Depth = 8,
    stride1Depth = 8,
  )

  it should "Compile TF float ResNet50V2 (ImageNet)" taggedAs (Slow) in {
    val name         = "resnet50v2_imagenet_float"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = ResNet50Float32Architecture,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/resnet50v2_imagenet.pb",
      List("Identity"),
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile TF fixed16bp8 ResNet50V2 (ImageNet)" taggedAs (Slow) in {
    val name         = "resnet50v2_imagenet_fixed16bp8"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = ResNet50Fp16bp8Architecture,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/resnet50v2_imagenet.pb",
      List("Identity"),
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile ONNX float ResNet20V2 (CIFAR)" taggedAs (Slow) in {
    val name         = "resnet20v2_cifar_float_onnx"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = ResNetFloat32Architecture,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/resnet20v2_cifar.onnx",
      List("Identity:0"),
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile ONNX fixed16bp8 ResNet20V2 (CIFAR)" in {
    val name         = "resnet20v2_cifar_fixed16bp8_onnx"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = ResNetFp16bp8Architecture,
      printSummary = true,
      printLayersSummary = true,
      collectBackendStats = true,
      printGraphFileName = Some(s"${name}.dot"),
      tracepointConditions = List(
        TracepointCondition(MemoryTag.Vars, "Identity:0")
      )
    )

    Compiler.compile(
      name,
      s"$Models/resnet20v2_cifar.onnx",
      List("Identity:0"),
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile ONNX float ResNet50V2 (ImageNet)" taggedAs (Slow) in {
    val name         = "resnet50v2_imagenet_float_onnx"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = ResNet50Float32Architecture,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/resnet50v2_imagenet.onnx",
      List("Identity:0"),
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile ONNX fixed16bp8 ResNet50V2 (ImageNet)" taggedAs (Slow) in {
    val name         = "resnet50v2_imagenet_fixed16bp8_onnx"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = ResNet50Fp16bp8Architecture,
      printSummary = true,
      printLayersSummary = true,
      collectBackendStats = true,
      printGraphFileName = Some(s"${name}.dot"),
      tracepointConditions = List(
        TracepointCondition(MemoryTag.Vars, "Identity:0")
      )
    )

    Compiler.compile(
      name,
      s"$Models/resnet50v2_imagenet.onnx",
      List("Identity:0"),
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile ONNX float YoloV4-tiny" taggedAs (Slow) in {
    val name         = "yolov4_tiny_192_8x8_float_onnx"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = YoloTinyFloat32Architecture,
      printSummary = true
    )

    Compiler.compile(
      name,
      s"$Models/yolov4_tiny_192.onnx",
      TinyYoloOnnx.GoldenOutputFileNames.keys.toList,
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }

  it should "Compile ONNX fixed16bp8 YoloV4-tiny" in {
    val name         = "yolov4_tiny_192_8x8_fixed16bp8_onnx"
    val traceContext = new ExecutiveTraceContext()
    val options = CompilerOptions(
      arch = YoloTinyFp16bp8Architecture,
      printSummary = true,
      printLayersSummary = true,
      collectBackendStats = true,
      printGraphFileName = Some(s"${name}.dot")
    )

    Compiler.compile(
      name,
      s"$Models/yolov4_tiny_192.onnx",
      TinyYoloOnnx.GoldenOutputFileNames.keys.toList,
      options,
      traceContext
    )

    GoldenProcessorHelper.test(
      name,
      inputBatchSize = options.inputBatchSize,
      traceContext = traceContext
    )
  }
}
