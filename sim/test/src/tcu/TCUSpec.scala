package tensil.tcu

import chisel3._
import chisel3.experimental.FixedPoint
import chiseltest._
import tensil.{Architecture, UnitSpec}
import tensil.data.{CartesianProduct, Index, Shape, Slice, Tensor, TensorReader}
import tensil.tcu.instruction._
import tensil.util.divCeil
import tensil.decoupled.{decoupledToDriver, decoupledVecToDriver}
import tensil.mem.MemControl
import tensil.tools.Xor
import tensil.InstructionLayout

import java.io.ByteArrayInputStream
import scala.reflect.ClassTag
import scala.collection.mutable
import chiseltest.internal.TesterThreadList
import tensil.ArchitectureDataType
import tensil.PlatformConfig
import tensil.mem.MemKind

class TCUSpec extends UnitSpec {
  behavior of "TCU"

  it should "handle matmul, datamove (local => acc) then SIMD" in {
    val gen = FixedPoint(16.W, 8.BP)
    val arch = Architecture.mkWithDefaults(
      arraySize = 8,
      accumulatorDepth = 64,
      localDepth = 64,
      stride0Depth = 8,
      stride1Depth = 8,
    )

    test(new TCU(gen, arch)) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()
      m.clock.setTimeout(2000)

      val n    = 5
      val size = arch.localDepth.toInt
      // val size = 0x1e

      val threads = new mutable.ArrayBuffer[TesterThreadList]

      threads += fork {
        m.io.instruction.enqueue(
          Instruction(
            Opcode.MatMul,
            MatMulFlags(true, false),
            MatMulArgs(0, 0, size / 4 - 1, 2, 2)
          )
        )
        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.memoryToAccumulator),
            DataMoveArgs(0, 0, size / 4 - 1, 2, 2)
          )
        )
        for (_ <- 0 until size) {
          m.io.instruction.enqueue(
            Instruction(
              Opcode.SIMD,
              SIMDFlags(true, true, true),
              SIMDArgs(0, 0, simd.Instruction(8, 0, 1, 0))
            )
          )
        }
      }

      threads.map(_.join())
    }
  }

  it should "handle alternating matmuls and datamove (acc => local)" in {
    val gen = FixedPoint(16.W, 8.BP)
    val arch = Architecture.mkWithDefaults(
      arraySize = 8,
      accumulatorDepth = 64,
      localDepth = 64,
      stride0Depth = 8,
      stride1Depth = 8,
    )

    test(new TCU(gen, arch)) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()
      m.clock.setTimeout(2000)

      val n    = 5
      val size = arch.localDepth.toInt
      // val size = 0x1e

      val threads = new mutable.ArrayBuffer[TesterThreadList]

      threads += fork {
        for (_ <- 0 until n) {
          m.io.instruction.enqueue(
            Instruction(
              Opcode.MatMul,
              MatMulFlags(true, false),
              MatMulArgs(0, 0, size - 1, 2, 2)
            )
          )
          m.io.instruction.enqueue(
            Instruction(
              Opcode.DataMove,
              DataMoveFlags(DataFlowControl.accumulatorToMemory),
              DataMoveArgs(0, 0, size - 1, 2, 2)
            )
          )
          m.io.instruction.enqueue(
            Instruction(
              Opcode.DataMove,
              DataMoveFlags(DataFlowControl.memoryToDram0),
              DataMoveArgs(0, 0, size - 1, 2, 2)
            )
          )
        }
      }

      threads += fork {
        for (_ <- 0 until n) {
          for (i <- 0 until size) {
            m.io.dram0.control
              .expectDequeue(
                MemControl(arch.dram0Depth, (i * (1 << 2)).U, 0.U, true.B)
              )
          }
        }
      }
      threads += fork {
        for (_ <- 0 until n) {
          for (_ <- 0 until size) {
            m.io.dram0.dataOut
              .expectDequeue(Array.fill(arch.arraySize)(0.F(16.W, 8.BP)))
          }
        }
      }

      threads.map(_.join())

    }
  }

  it should "run many SIMDs" in {
    val gen = FixedPoint(16.W, 8.BP)
    val arch = Architecture.mkWithDefaults(
      arraySize = 8,
      accumulatorDepth = 64,
      localDepth = 64,
      stride0Depth = 8,
      stride1Depth = 8,
    )

    test(new TCU(gen, arch)) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()
      m.clock.setTimeout(1000)

      val n    = 1
      val size = 64

      val threads = new mutable.ArrayBuffer[TesterThreadList]

      threads += fork {
        for (_ <- 0 until n) {
          m.io.instruction.enqueue(
            Instruction(
              Opcode.MatMul,
              MatMulFlags(false, false),
              MatMulArgs(0, 0, size - 1, 2, 2)
            )
          )
        }
        for (_ <- 0 until n * size) {
          m.io.instruction.enqueue(
            Instruction(
              Opcode.SIMD,
              SIMDFlags(true, true, true),
              SIMDArgs(0, 0, simd.Instruction(8, 0, 1, 0))
            )
          )
        }
        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.memoryToDram0),
            DataMoveArgs(0, 0, size - 1, 2, 2)
          )
        )
      }
      threads += fork {
        for (i <- 0 until size) {
          m.io.dram0.control
            .expectDequeue(
              MemControl(arch.dram0Depth, (i * (1 << 2)).U, 0.U, true.B)
            )
        }
      }
      threads += fork {
        for (_ <- 0 until size) {
          m.io.dram0.dataOut
            .expectDequeue(Array.fill(arch.arraySize)(0.F(16.W, 8.BP)))
        }
      }

      threads.map(_.join())
    }
  }

  it should "emit many data outs" in {
    val gen = FixedPoint(16.W, 8.BP)
    val arch = Architecture.mkWithDefaults(
      arraySize = 8,
      accumulatorDepth = 64,
      localDepth = 64,
      stride0Depth = 8,
      stride1Depth = 8,
    )

    test(new TCU(gen, arch)) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()
      m.clock.setTimeout(2000)

      val n    = 10
      val size = 0x1f + 1

      val threads = new mutable.ArrayBuffer[TesterThreadList]

      threads += fork {
        for (_ <- 0 until n) {
          m.io.instruction.enqueue(Instruction.fromUInt("h211f0000000000".U))
        }
      }
      threads += fork {
        for (_ <- 0 until n) {
          m.io.dram0.control
            .expectDequeue(
              MemControl(arch.dram0Depth, 0.U, (size - 1).U, true.B)
            )
        }
      }
      threads += fork {
        for (_ <- 0 until n * size) {
          m.io.dram0.dataOut
            .expectDequeue(Array.fill(arch.arraySize)(0.F(16.W, 8.BP)))
        }
      }

      threads.map(_.join())
    }
  }

  it should "not stall because of full router mux buffers" in {
    val gen = FixedPoint(16.W, 8.BP)
    val arch = Architecture.mkWithDefaults(
      arraySize = 8,
      accumulatorDepth = 64,
      localDepth = 64,
      stride0Depth = 8,
      stride1Depth = 8,
    )

    test(new TCU(gen, arch)) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()
      m.clock.setTimeout(1000)

      val program = Array(
        "h30000000000000",
        "h101e0000000000",
        "h12200000000000",
        "h121d0000000000",
        "h30000000000000",
        "h31000000000000",
        "h11df0000000000",
        "h131f0000000000",
        "h30000000000000",
        "h31000000000000",
        "h111e0000000000",
        "h131f0000000000",
        "h131e0000000000",
        "h30000000000000",
        "h31000000000000",
        "h111e0000000000",
        "h111e0000000000",
        "h111e0000000000",
        "h00000000000000",
        "h00000000000000",
        "h00000000000000",
        "h00000000000000",
        "h00000000000000",
      )

      val threads = new mutable.ArrayBuffer[TesterThreadList]
      // write in program
      threads += fork {
        for (instruction <- program) {
          m.io.instruction.enqueue(Instruction.fromUInt(instruction.U))
        }
        m.clock.step(100)
      }

      // expect to reach end
      threads.map(_.join())
    }
  }

  it should "not stall when array output buffer is overfilled" in {
    val gen = FixedPoint(16.W, 8.BP)
    val arch = Architecture.mkWithDefaults(
      arraySize = 8,
      accumulatorDepth = 64,
      localDepth = 64,
      stride0Depth = 8,
      stride1Depth = 8,
    )

    test(new TCU(gen, arch)) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()
      m.clock.setTimeout(1000)

      val program = Array(
        "h30000000000000",
        "h101e0000000000",
        "h12200000000000",
        "h12101000000000",
        "h30000000000000",
        "h31000000000000",
        "h11200000000000",
        "h131f0000000000",
        "h30000000000000",
        "h31000000000000",
        "h111e0000000000",
        "h111e0000000000",
        "h00000000000000",
        "h00000000000000",
        "h00000000000000",
        "h00000000000000",
        "h00000000000000",
      )

      val threads = new mutable.ArrayBuffer[TesterThreadList]
      // write in program
      threads += fork {
        for (instruction <- program) {
          m.io.instruction.enqueue(Instruction.fromUInt(instruction.U))
        }
        m.clock.step(100)
      }

      // expect to reach end
      threads.map(_.join())
    }
  }

  it should "work when datamove dram1 latency is high" in {
    val gen              = SInt(8.W)
    val arrayWidth       = 8
    val memoryDepth      = 256
    val accumulatorDepth = 256
    val arch = Architecture.mkWithDefaults(
      arraySize = arrayWidth,
      localDepth = memoryDepth,
      accumulatorDepth = accumulatorDepth
    )

    test(new TCU(gen, arch)) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      val weightsData: Array[Array[BigInt]] = Array(
        Array(0, 0, 0, 0, 0, 0, 0, 0),
        Array(1, 0, 0, 0, 0, 0, 0, 0),
        Array(0, 1, 0, 0, 0, 0, 0, 0),
        Array(0, 0, 1, 0, 0, 0, 0, 0),
        Array(0, 0, 0, 1, 0, 0, 0, 0),
        Array(0, 0, 0, 0, 1, 0, 0, 0),
        Array(0, 0, 0, 0, 0, 1, 0, 0),
        Array(0, 0, 0, 0, 0, 0, 1, 0),
        Array(0, 0, 0, 0, 0, 0, 0, 1),
      )
      val weights = Tensor(weightsData)
      val input   = new Tensor(Array(1, 1, 1, 1, 1, 1, 1, 1), Shape(1, 8))

      m.dataIn(input, 9)

      val size = 9

      val t0 = fork {
        m.io.dram1.control
          .expectDequeue(
            MemControl(layout.arch.dram1Depth, 0.U, (size - 1).U, false.B)
          )
        m.clock.step(30)
        for (i <- 0 until size) {
          m.io.dram1.dataIn.enqueue(weightsData(i).map(_.S))
        }
      }

      m.io.instruction.enqueue(
        Instruction(
          Opcode.DataMove,
          DataMoveFlags(DataFlowControl.dram1ToMemory),
          DataMoveArgs(0, 0, size - 1)
        )
      )
      m.loadWeights(0, arrayWidth + 1)
      m.matMul(9, 0, 1, accumulate = false, zeroes = false)
      m.dataMove(10, 0, 1)

      t0.join()

      val output: Tensor[BigInt] = m.dataOutSingleInstruction(Shape(1, 8), 10)

      assert(output == input)
    }
  }

  it should "datamove and accumulate" in {
    val gen              = FixedPoint(16.W, 8.BP)
    val arrayWidth       = 8
    val memoryDepth      = 256
    val accumulatorDepth = 256
    val arch = Architecture.mkWithDefaults(
      arraySize = arrayWidth,
      localDepth = memoryDepth,
      accumulatorDepth = accumulatorDepth
    )

    decoupledTest(new TCU(gen, arch)) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      val testData = for (i <- 0 until arrayWidth) yield i.F(gen.binaryPoint)
      val expectData =
        for (i <- 0 until arrayWidth) yield (2 * i).F(gen.binaryPoint)

      thread("instruction") {
        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.dram0ToMemory),
            DataMoveArgs(0, 0, 0)
          )
        )
      }
      thread("dram0.control") {
        m.io.dram0.control
          .expectDequeue(MemControl(m.arch.dram0Depth)(0.U, false.B))
      }
      thread("dram0.dataIn") {
        m.io.dram0.dataIn.enqueue(testData)
      }

      thread("instruction") {
        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.memoryToAccumulator),
            DataMoveArgs(0, 0, 0)
          )
        )
        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.memoryToAccumulatorAccumulate),
            DataMoveArgs(0, 0, 0)
          )
        )

        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.accumulatorToMemory),
            DataMoveArgs(1, 0, 0)
          )
        )
        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.memoryToDram0),
            DataMoveArgs(1, 0, 0)
          )
        )
      }
      thread("dram0.control") {
        m.io.dram0.control
          .expectDequeue(MemControl(m.arch.dram0Depth)(0.U, true.B))
      }
      thread("dram0.dataOut") {
        m.io.dram0.dataOut.expectDequeue(expectData)
      }

    }
  }

  it should "matmul with zeroes flag" in {
    val gen              = SInt(8.W)
    val arrayWidth       = 4
    val memoryDepth      = 16
    val accumulatorDepth = 16
    val arch = Architecture.mkWithDefaults(
      arraySize = arrayWidth,
      localDepth = memoryDepth,
      accumulatorDepth = accumulatorDepth
    )

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      val weightsData: Array[Array[BigInt]] = Array(
        Array(1, 2, 3, 4),
        Array(5, 6, 7, 8),
        Array(9, 10, 11, 12),
        Array(13, 14, 15, 16),
        Array(17, 18, 19, 20),
      )
      val weights  = Tensor(weightsData)
      val input    = new Tensor(Array(21, 22, 23, 24), Shape(1, 4))
      val expected = new Tensor(Array(1, 2, 3, 4), Shape(1, 4)) // just the bias

      m.weightsIn(weights, 4)
      m.dataIn(input, 0)

      m.loadWeights(4, 5)

      m.matMul(0, 0, 1, accumulate = false, zeroes = true)
      m.dataMove(Array(0), Array(0))
      val output: Tensor[BigInt] = m.dataOut(Shape(1, 4), 0)

      assert(output == expected)
    }
  }

  it should "matmul with zeroes repeatedly without stalling" in {
    val gen              = SInt(8.W)
    val arrayWidth       = 4
    val memoryDepth      = 16
    val accumulatorDepth = 16
    val arch = Architecture.mkWithDefaults(
      arraySize = arrayWidth,
      localDepth = memoryDepth,
      accumulatorDepth = accumulatorDepth
    )

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      val weightsData: Array[Array[BigInt]] = Array(
        Array(1, 2, 3, 4),
        Array(5, 6, 7, 8),
        Array(9, 10, 11, 12),
        Array(13, 14, 15, 16),
        Array(17, 18, 19, 20),
      )
      val weights  = Tensor(weightsData)
      val input    = new Tensor(Array(21, 22, 23, 24), Shape(1, 4))
      val expected = new Tensor(Array(1, 2, 3, 4), Shape(1, 4)) // just the bias

      m.weightsIn(weights, 4)
      m.dataIn(input, 0)

      m.loadWeights(4, 5)

      for (_ <- 0 until 20) {
        m.matMul(0, 0, 1, accumulate = false, zeroes = true)
      }
    }
  }

  it should "not stall when loading weights" in {
    val gen              = FixedPoint(16.W, 8.BP)
    val arrayWidth       = 4
    val memoryDepth      = 256
    val accumulatorDepth = 256
    val weightDepth      = 256
    val arch = Architecture.mkWithDefaults(
      arraySize = arrayWidth,
      localDepth = memoryDepth,
      accumulatorDepth = accumulatorDepth
    )

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      val weights0 = Array(
        Array(0, 0, 0, 0),
        Array(1, 0, 0, 0),
        Array(0, 2, 0, 0),
        Array(0, 0, 3, 0),
        Array(0, 0, 0, 4),
      ).map(_.map(_.F(16.W, 8.BP)))
      val weights1 = Array(
        Array(0, 0, 0, 0),
        Array(5, 0, 0, 0),
        Array(0, 6, 0, 0),
        Array(0, 0, 7, 0),
        Array(0, 0, 0, 8),
      ).map(_.map(_.F(16.W, 8.BP)))
      val input   = Array(1, 1, 1, 1).map(_.F(16.W, 8.BP))
      val output0 = Array(1, 2, 3, 4).map(_.F(16.W, 8.BP))
      val output1 = Array(5, 6, 7, 8).map(_.F(16.W, 8.BP))

      val covered = Array(false, false, false)

      fork {
        m.io.dram1.control
          .expectDequeue(MemControl(m.arch.dram1Depth, 0.U, 4.U, false.B))
        for (i <- weights0.indices) {
          m.io.dram1.dataIn.enqueue(weights0(i))
        }

        covered(0) = true
      }

      m.io.instruction.enqueue(
        Instruction(
          Opcode.DataMove,
          DataMoveFlags(DataFlowControl.dram1ToMemory),
          DataMoveArgs(4, 0, 4)
        )
      )

      fork {
        m.io.dram0.control
          .expectDequeue(MemControl(m.arch.dram0Depth)(0.U, false.B))
        m.io.dram0.dataIn.enqueue(input)

        covered(1) = true
      }

      m.io.instruction.enqueue(
        Instruction(
          Opcode.DataMove,
          DataMoveFlags(DataFlowControl.dram0ToMemory),
          DataMoveArgs(0, 0, 0)
        )
      )

      m.io.instruction.enqueue(
        Instruction(
          Opcode.LoadWeights,
          LoadWeightFlags(false),
          LoadWeightArgs(4, 4)
        )
      )
      m.io.instruction.enqueue(
        Instruction(Opcode.MatMul, MatMulFlags(false), MatMulArgs(0, 0, 0))
      )
      m.io.instruction
        .enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.accumulatorToMemory),
            DataMoveArgs(1, 0, 0)
          )
        )
      val t0 = fork {
        m.io.dram0.control
          .expectDequeue(MemControl(m.arch.dram0Depth)(1.U, true.B))
        m.io.dram0.dataOut.expectDequeue(output0)
      }
      m.io.instruction.enqueue(
        Instruction(
          Opcode.DataMove,
          DataMoveFlags(DataFlowControl.memoryToDram0),
          DataMoveArgs(1, 1, 0)
        )
      )
      t0.join()

      fork {
        m.io.dram1.control
          .expectDequeue(MemControl(m.arch.dram1Depth, 5.U, 4.U, false.B))
        for (i <- weights1.indices) {
          m.io.dram1.dataIn.enqueue(weights1(i))
        }

        covered(2) = true
      }

      m.io.instruction.enqueue(
        Instruction(
          Opcode.DataMove,
          DataMoveFlags(DataFlowControl.dram1ToMemory),
          DataMoveArgs(9, 5, 4)
        )
      )

      m.io.instruction.enqueue(
        Instruction(
          Opcode.LoadWeights,
          LoadWeightFlags(false),
          LoadWeightArgs(9, 4)
        )
      )
      m.io.instruction.enqueue(
        Instruction(Opcode.MatMul, MatMulFlags(false), MatMulArgs(0, 0, 0))
      )
      m.io.instruction
        .enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.accumulatorToMemory),
            DataMoveArgs(1, 0, 0)
          )
        )

      val t1 = fork {
        m.io.dram0.control
          .expectDequeue(MemControl(m.arch.dram0Depth)(1.U, true.B))
        m.io.dram0.dataOut.expectDequeue(output1)
      }
      m.io.instruction.enqueue(
        Instruction(
          Opcode.DataMove,
          DataMoveFlags(DataFlowControl.memoryToDram0),
          DataMoveArgs(1, 1, 0)
        )
      )
      t1.join()

      covered.map(assert(_))
    }
  }

  it should "not deadlock" in {
    val gen              = FixedPoint(16.W, 8.BP)
    val arrayWidth       = 4
    val memoryDepth      = 256
    val accumulatorDepth = 256
    val weightDepth      = 256
    val arch = Architecture.mkWithDefaults(
      arraySize = arrayWidth,
      localDepth = memoryDepth,
      accumulatorDepth = accumulatorDepth
    )

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      m.io.instruction.enqueue(
        Instruction(
          Opcode.SIMD,
          SIMDFlags(read = false, write = false, accumulate = false),
          SIMDArgs(
            0,
            0,
            simd.Instruction(
              simd.Op.Increment,
              1,
              1,
              1,
            )
          )
        )
      )

      for (i <- 0 until 100) {
        m.io.instruction.enqueue(
          Instruction(
            Opcode.SIMD,
            SIMDFlags(read = true, write = true, accumulate = false),
            SIMDArgs(
              i,
              i + 1,
              simd.Instruction(
                simd.Op.Add,
                0,
                1,
                1,
              )
            )
          )
        )
      }

    }
  }

  it should "run the identity model" in {
    val gen              = FixedPoint(16.W, 8.BP)
    val arrayWidth       = 4
    val memoryDepth      = 256
    val accumulatorDepth = 256
    val weightDepth      = 256
    val weightOffset     = 128
    val arch = Architecture.mkWithDefaults(
      arraySize = arrayWidth,
      localDepth = memoryDepth,
      accumulatorDepth = accumulatorDepth
    )

    def f(x: Int): FixedPoint = {
      x.F(gen.getWidth.W, gen.binaryPoint)
    }

    val identity = Array(
      Array(1, 0, 0, 0),
      Array(0, 1, 0, 0),
      Array(0, 0, 1, 0),
      Array(0, 0, 0, 1),
    )
    val data = Array(
      Array(0, 1, 2, 3),
      Array(4, 5, 6, 7),
    )
    val inputAddress  = 0
    val outputAddress = 2

    decoupledTest(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      thread("dram1.control") {
        m.clock.step(50)
        m.io.dram1.control.expectDequeue(
          MemControl(
            m.dram1Depth,
            weightOffset.U,
            (identity.length - 1).U,
            false.B
          )
        )
      }

      thread("dram1.dataIn") {
        m.clock.step(50)
        for (row <- identity) {
          m.io.dram1.dataIn.enqueue(row.map(f))
        }
      }

      thread("dram0.control") {
        m.clock.step(100)
        m.io.dram0.control
          .expectDequeue(
            MemControl(
              m.dram0Depth,
              inputAddress.U,
              (data.length - 1).U,
              false.B
            )
          )
        m.clock.step(150)
        m.io.dram0.control
          .expectDequeue(
            MemControl(
              m.dram0Depth,
              outputAddress.U,
              (data.length - 1).U,
              true.B
            )
          )
      }

      thread("dram0.dataIn") {
        m.clock.step(100)
        for (row <- data) {
          m.io.dram0.dataIn.enqueue(row.map(f))
        }
      }

      thread("dram0.dataOut") {
        m.clock.step(250)
        for (row <- data) {
          m.io.dram0.dataOut.expectDequeue(row.map(f))
        }
      }

      thread("instruction") {
        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.dram1ToMemory),
            DataMoveArgs(weightOffset, weightOffset, identity.length - 1)
          )
        )
        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.dram0ToMemory),
            DataMoveArgs(inputAddress, inputAddress, data.length - 1)
          )
        )

        m.io.instruction.enqueue(
          Instruction(
            Opcode.LoadWeights,
            LoadWeightFlags(false),
            LoadWeightArgs(weightOffset, identity.length - 1)
          )
        )
        m.io.instruction.enqueue(
          Instruction(
            Opcode.LoadWeights,
            LoadWeightFlags(true),
            LoadWeightArgs(0, 0)
          )
        )
        m.io.instruction.enqueue(
          Instruction(
            Opcode.MatMul,
            MatMulFlags(false),
            MatMulArgs(inputAddress, 0, data.length - 1)
          )
        )

        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.accumulatorToMemory),
            DataMoveArgs(outputAddress, 0, data.length - 1)
          )
        )

        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.memoryToDram0),
            DataMoveArgs(outputAddress, outputAddress, data.length - 1)
          )
        )
      }
    }
  }

  it should "work when the weights data is delayed behind the data move instruction" in {
    val gen              = UInt(8.W)
    val arrayWidth       = 4
    val memoryDepth      = 256
    val accumulatorDepth = 256
    val weightDepth      = 256
    val weightsOffset    = 128
    val arch = Architecture.mkWithDefaults(
      arraySize = arrayWidth,
      localDepth = memoryDepth,
      accumulatorDepth = accumulatorDepth
    )

    decoupledTest(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      thread("dram1.control") {
        m.clock.step(50)
        m.io.dram1.control
          .expectDequeue(
            MemControl(m.arch.dram1Depth)((weightsOffset + 0).U, false.B)
          )
        m.io.dram1.control
          .expectDequeue(
            MemControl(m.arch.dram1Depth)((weightsOffset + 1).U, false.B)
          )
        m.io.dram1.control
          .expectDequeue(
            MemControl(m.arch.dram1Depth)((weightsOffset + 2).U, false.B)
          )
        m.io.dram1.control
          .expectDequeue(
            MemControl(m.arch.dram1Depth)((weightsOffset + 3).U, false.B)
          )
      }
      thread("dram1.dataIn") {
        m.clock.step(50)

        m.io.dram1.dataIn.enqueue(Array(0, 1, 2, 3).map(_.U))
        m.io.dram1.dataIn.enqueue(Array(4, 5, 6, 7).map(_.U))
        m.io.dram1.dataIn.enqueue(Array(8, 9, 10, 11).map(_.U))
        m.io.dram1.dataIn
          .enqueue(Array(12, 13, 14, 15).map(_.U))
      }

      thread("dram0.control") {
        m.clock.step(50)
        m.io.dram0.control
          .expectDequeue(MemControl(m.arch.dram0Depth)(0.U, false.B))
        m.io.dram0.control
          .expectDequeue(MemControl(m.arch.dram0Depth)(1.U, false.B))
        m.io.dram0.control
          .expectDequeue(MemControl(m.arch.dram0Depth)(2.U, false.B))
        m.io.dram0.control
          .expectDequeue(MemControl(m.arch.dram0Depth)(3.U, false.B))

        for (i <- 4 until 8) {
          m.io.dram0.control
            .expectDequeue(MemControl(m.arch.dram0Depth)(i.U, true.B))
        }
      }

      thread("dram0.dataIn") {
        m.clock.step(50)
        m.io.dram0.dataIn.enqueue(Array(16, 17, 18, 19).map(_.U))
        m.io.dram0.dataIn.enqueue(Array(20, 21, 22, 23).map(_.U))
        m.io.dram0.dataIn.enqueue(Array(24, 25, 26, 27).map(_.U))
        m.io.dram0.dataIn.enqueue(Array(28, 29, 30, 31).map(_.U))
      }

      thread("dram0.dataOut") {
        m.clock.step(150)
        val expected: Array[Array[BigInt]] = Array(
          Array(184, 254, 68, 138),
          Array(24, 110, 196, 26),
          Array(120, 222, 68, 170),
          Array(216, 78, 196, 58),
        )
        for (row <- expected) {
          m.io.dram0.dataOut.expectDequeue(row.map(_.U))
        }
      }

      // fork {
      //   val out =
      //     m.dataOutNoInstruction[BigInt](Shape(4, 4), 4, enableSize = false)
      //   val expected: Array[Array[BigInt]] = Array(
      //     Array(184, 254, 68, 138),
      //     Array(24, 110, 196, 26),
      //     Array(120, 222, 68, 170),
      //     Array(216, 78, 196, 58),
      //   )
      //   val expectedT = new Tensor(expected.flatten, Shape(4, 4))
      //   assert(out == expectedT)

      //   covered(2) = true
      // }

      thread("instruction") {
        for (i <- 0 until 4) {
          m.io.instruction.enqueue(
            Instruction(
              Opcode.DataMove,
              DataMoveFlags(DataFlowControl.dram1ToMemory),
              DataMoveArgs(i + weightsOffset, i + weightsOffset, 0)
            )
          )
        }

        for (i <- 0 until 4) {
          m.io.instruction.enqueue(
            Instruction(
              Opcode.DataMove,
              DataMoveFlags(DataFlowControl.dram0ToMemory),
              DataMoveArgs(i, i, 0)
            )
          )
        }

        m.io.instruction.enqueue(
          Instruction(
            Opcode.LoadWeights,
            LoadWeightFlags(false),
            LoadWeightArgs(0 + weightsOffset, 3)
          )
        )
        m.io.instruction.enqueue(
          Instruction(
            Opcode.LoadWeights,
            LoadWeightFlags(true),
            LoadWeightArgs(0 + weightsOffset, 0)
          )
        )

        m.io.instruction.enqueue(
          Instruction(Opcode.MatMul, MatMulFlags(false), MatMulArgs(0, 0, 3))
        )

        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.accumulatorToMemory),
            DataMoveArgs(4, 0, 3)
          )
        )

        for (i <- 4 until 8) {
          m.io.instruction.enqueue(
            Instruction(
              Opcode.DataMove,
              DataMoveFlags(DataFlowControl.memoryToDram0),
              DataMoveArgs(i, i, 0)
            )
          )
        }
      }
    }
  }

  it should "do a 4x4 matmul on 4x4 array with 16-bit data" in {
    val gen              = SInt(16.W)
    val arrayWidth       = 4
    val memoryDepth      = 256
    val accumulatorDepth = 256
    val weightsDepth     = 256
    val weightsOffset    = 128
    val arch = Architecture.mkWithDefaults(
      arraySize = arrayWidth,
      localDepth = memoryDepth,
      accumulatorDepth = accumulatorDepth
    )

    val weights =
      new Tensor((for (i <- 0 until 16) yield i).toArray, Shape(4, 4))
    val input =
      new Tensor((for (i <- 16 until 32) yield i).toArray, Shape(4, 4))
    val expected = input * weights

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      m.weightsInSingleInstruction(weights, 0 + weightsOffset)
      m.dataInSingleInstruction(input, 0)
      m.loadWeights(0 + weightsOffset, 4)
      m.loadWeightsZeroes(1) // bias = 0
      m.matMul(0, 0, 4, accumulate = false, zeroes = false)
      m.dataMove(4, 0, 4)

      m.noOp(5)

      val output: Tensor[BigInt] =
        m.dataOutSingleInstruction(Shape(4, m.arch.arraySize), 4)
      assert(output == expected)
    }
  }

  it should "perform a matmul of size > 1 with single instruction" in {
    val gen              = SInt(16.W)
    val arrayWidth       = 2
    val weightsDepth     = 8
    val memoryDepth      = 16
    val accumulatorDepth = 8
    val weightsOffset    = 8
    val arch = Architecture.mkWithDefaults(
      arraySize = arrayWidth,
      localDepth = memoryDepth,
      accumulatorDepth = accumulatorDepth
    )

    val weights  = new Tensor(Array(1, 2, 3, 4), Shape(2, 2))
    val bias     = new Tensor(Array(5, 6), Shape(1, 2))
    val input    = new Tensor(Array(7, 8, 9, 10), Shape(2, 2))
    val expected = input * weights + bias.broadcast(0, 2)

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      m.weightsInSingleInstruction(weights, 0 + weightsOffset)
      m.weightsInSingleInstruction(bias, 2 + weightsOffset)
      m.dataInSingleInstruction(input, 0)
      m.loadWeights(0 + weightsOffset, 2)
      m.loadWeights(2 + weightsOffset, 1)
      m.matMul(0, 0, 2, accumulate = false, zeroes = false)
      m.dataMove(2, 0, 2)

      m.noOp(5)

      val output: Tensor[BigInt] =
        m.dataOutSingleInstruction(Shape(2, m.arch.arraySize), 2)
      assert(output == expected)
    }
  }

  it should "run XOR with 2x2 array for 256 memory depth" in {
    val gen              = FixedPoint(32.W, 16.BP)
    val arrayWidth       = 2
    val weightsDepth     = 256
    val memoryDepth      = 256
    val accumulatorDepth = 256
    val weightsOffset    = 128
    val arch = Architecture.mkWithDefaults(
      arraySize = arrayWidth,
      localDepth = memoryDepth,
      accumulatorDepth = accumulatorDepth
    )

    val weightsData = Array(
      // sequential_10/dense_24/BiasAdd/ReadVariableOp
      // 0,0
      0x3b, 0xa3, 0x9a, 0xb1,
      // 0,1
      0, 0, 0, 0,
      // sequential_10/dense_24/MatMul/ReadVariableOp
      // 0,0
      0x3f, 0x9a, 0xa2, 0x4a,
      // 0,1
      0, 0, 0, 0,
      // 1,0
      0x3f, 0x9f, 0x22, 0xec,
      // 1,1
      0, 0, 0, 0,
      // sequential_10/dense_23/BiasAdd/ReadVariableOp
      // 0,0
      0xb7, 0x22, 0x13, 0x89,
      // 0,1
      0xbd, 0xe7, 0xa0, 0x89,
      // sequential_10/dense_23/MatMul/ReadVariableOp
      // 0,0
      0xbf, 0x52, 0x1, 0xdc,
      // 0,1
      0x3f, 0x69, 0x3c, 0xb4,
      // 1,0
      0x3f, 0x52, 0x1, 0xc3,
      // 1,1
      0xbf, 0x4c, 0x47, 0xd6
    ).map(_.toByte)
    val weights =
      Tensor(
        new TensorReader(
          new ByteArrayInputStream(weightsData),
          arrayWidth
        ).toArray
      ).map(BigDecimal.binary(_))
    val input = Tensor(
      new TensorReader(
        new ByteArrayInputStream(
          Xor.prepareInputBytes(ArchitectureDataType.FLOAT32, arrayWidth, 4)
        ),
        arrayWidth
      ).toArray
    ).map(BigDecimal.binary(_))

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      m.clock.setTimeout(100)

      m.tpuExecutiveGoldenTest(
        weights,
        input(Index(Left(0), Right(Slice.all))),
        BigDecimal.binary(0),
        BigDecimal.binary(0.01),
        weightsOffset
      )
      m.tpuExecutiveGoldenTest(
        weights,
        input(Index(Left(1), Right(Slice.all))),
        BigDecimal.binary(1),
        BigDecimal.binary(0.01),
        weightsOffset
      )
      m.tpuExecutiveGoldenTest(
        weights,
        input(Index(Left(2), Right(Slice.all))),
        BigDecimal.binary(1),
        BigDecimal.binary(0.01),
        weightsOffset
      )
      m.tpuExecutiveGoldenTest(
        weights,
        input(Index(Left(3), Right(Slice.all))),
        BigDecimal.binary(0),
        BigDecimal.binary(0.01),
        weightsOffset
      )

    }
  }

  it should "compute each individual matmul from the XOR network correctly" in {
    val gen              = FixedPoint(32.W, 16.BP)
    val arrayWidth       = 2
    val weightsDepth     = 256
    val memoryDepth      = 256
    val accumulatorDepth = 256
    val weightsOffset    = 128
    val arch = Architecture.mkWithDefaults(
      arraySize = arrayWidth,
      localDepth = memoryDepth,
      accumulatorDepth = accumulatorDepth
    )

    val weightsData = Array(
      // sequential_10/dense_24/BiasAdd/ReadVariableOp
      // 0,0
      0x3b, 0xa3, 0x9a, 0xb1,
      // 0,1
      0, 0, 0, 0,
      // sequential_10/dense_24/MatMul/ReadVariableOp
      // 0,0
      0x3f, 0x9a, 0xa2, 0x4a,
      // 0,1
      0, 0, 0, 0,
      // 1,0
      0x3f, 0x9f, 0x22, 0xec,
      // 1,1
      0, 0, 0, 0,
      // sequential_10/dense_23/BiasAdd/ReadVariableOp
      // 0,0
      0xb7, 0x22, 0x13, 0x89,
      // 0,1
      0xbd, 0xe7, 0xa0, 0x89,
      // sequential_10/dense_23/MatMul/ReadVariableOp
      // 0,0
      0xbf, 0x52, 0x1, 0xdc,
      // 0,1
      0x3f, 0x69, 0x3c, 0xb4,
      // 1,0
      0x3f, 0x52, 0x1, 0xc3,
      // 1,1
      0xbf, 0x4c, 0x47, 0xd6
    ).map(_.toByte)
    val weights = Tensor(
      new TensorReader(
        new ByteArrayInputStream(weightsData),
        arrayWidth
      ).toArray
    ).map(BigDecimal.binary(_))

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      val input =
        new Tensor(Array(1.0f, 0.0f), Shape(1, 2)).map(BigDecimal.binary(_))
      val expected = new Tensor(Array(1.21307048f, 0f), Shape(1, 2))
        .map(BigDecimal.binary(_))

      m.weightsInSingleInstruction(weights, 0 + weightsOffset)
      m.dataIn(input, 0)
      m.loadWeights(1 + weightsOffset, 2)
      m.loadWeights(0 + weightsOffset, 1)
      m.matMul(Array(0), accumulate = false, Array(0))
      m.dataMove(1, 0, 1)
      m.noOp(6)
      val output: Tensor[BigDecimal] =
        m.dataOut(Shape(1, m.arch.arraySize), 1)
      assert(output.equalsP(expected, BigDecimal.binary(0.0001)))
    }

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      val input = new Tensor(Array(1.21307048f, 0f), Shape(1, 2))
        .map(BigDecimal.binary(_))
      val expected = new Tensor(Array(-0.99514095f, 0.99210812f), Shape(1, 2))
        .map(BigDecimal.binary(_))

      m.weightsInSingleInstruction(weights, 0 + weightsOffset)
      m.dataIn(input, 0)
      m.loadWeights(4 + weightsOffset, 2)
      m.loadWeights(3 + weightsOffset, 1)
      m.matMul(Array(0), accumulate = false, Array(0))
      m.dataMove(1, 0, 1)
      m.noOp(6)
      val output: Tensor[BigDecimal] =
        m.dataOut(Shape(1, m.arch.arraySize), 1)
      assert(output.equalsP(expected, BigDecimal.binary(0.0001)))
    }

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      val input = new Tensor(Array(1.0f, 0f), Shape(1, 2))
        .map(BigDecimal.binary(_))
      val expected = new Tensor(Array(-0.99514095f, 0.99210812f), Shape(1, 2))
        .map(BigDecimal.binary(_))

      m.weightsInSingleInstruction(weights, 0 + weightsOffset)
      m.dataIn(input, 0)

      m.loadWeights(1 + weightsOffset, 2)
      m.loadWeights(0 + weightsOffset, 1)
      m.matMul(Array(0), accumulate = false, Array(0))
      m.dataMove(1, 0, 1)
      m.noOp(3)

      m.loadWeights(4 + weightsOffset, 2)
      m.loadWeights(3 + weightsOffset, 1)
      m.matMul(Array(1), accumulate = false, Array(1))
      m.dataMove(2, 1, 1)

      m.noOp(6)

      val output: Tensor[BigDecimal] =
        m.dataOut(Shape(1, m.arch.arraySize), 2)
      assert(output.equalsP(expected, BigDecimal.binary(0.0001)))
    }
  }

  it should "load weights with load size > 1" in {
    val gen            = SInt(16.W)
    val width          = 2
    val depth          = 8
    val memDepth       = 16
    val weightMemDepth = 8
    val weightsOffset  = 8
    val arch = Architecture.mkWithDefaults(
      arraySize = width,
      localDepth = memDepth,
      accumulatorDepth = depth
    )

    val weights = new Tensor(Array(1, 2, 3, 4), Shape(2, 2))
    val input   = new Tensor(Array(7, 8, 9, 10), Shape(2, 2))
    val expected = Tensor(Tensor.goldenMatMatMul(input.to2D(), weights.to2D()))
      .map(BigInt(_))

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      m.weightsInSingleInstruction(weights, 0 + weightsOffset)
      m.dataIn(input, 0)
      m.loadWeights(0 + weightsOffset, 2)
      m.loadWeightsZeroes(1)
      m.matMul(Array(0), accumulate = false, Array(0))
      m.matMul(Array(1), accumulate = false, Array(1))
      m.dataMove(2, 0, 1)
      m.dataMove(3, 1, 1)
      m.noOp(3)
      val output: Tensor[BigInt] =
        m.dataOut(Shape(2, m.arch.arraySize), 2)
      assert(output == expected)
    }
  }

  it should "do a convolution that exceeds the limits of the accumulators" in {
    val gen            = SInt(16.W)
    val width          = 2
    val depth          = 8
    val memDepth       = 72
    val weightMemDepth = 36
    val weightsOffset  = 36
    val arch = Architecture.mkWithDefaults(
      arraySize = width,
      localDepth = memDepth,
      accumulatorDepth = depth
    )

    // 4 input channels, 4 output channels
    // 3x3 image
    // 2x2 convolution
    val image: Array[Array[Array[BigInt]]] = Array(
      Array(
        Array(0, 1, 2, 3),
        Array(4, 5, 6, 7),
        Array(8, 9, 10, 11),
      ),
      Array(
        Array(12, 13, 14, 15),
        Array(16, 17, 18, 19),
        Array(20, 21, 22, 23),
      ),
      Array(
        Array(24, 25, 26, 27),
        Array(28, 29, 30, 31),
        Array(32, 33, 34, 35),
      )
    )
    val img = new Tensor(image.map(_.flatten).flatten, Shape(3, 3, 4))
    val filter: Array[Array[Array[Array[BigInt]]]] = Array(
      Array(
        Array(
          Array(36, 37, 38, 39),
          Array(40, 41, 42, 43),
          Array(44, 45, 46, 47),
          Array(48, 49, 50, 51),
        ),
        Array(
          Array(52, 53, 54, 55),
          Array(56, 57, 58, 59),
          Array(60, 61, 62, 63),
          Array(64, 65, 66, 67),
        ),
      ),
      Array(
        Array(
          Array(68, 69, 70, 71),
          Array(72, 73, 74, 75),
          Array(76, 77, 78, 79),
          Array(80, 81, 82, 83),
        ),
        Array(
          Array(84, 85, 86, 87),
          Array(88, 89, 90, 91),
          Array(92, 93, 94, 95),
          Array(96, 97, 98, 99),
        ),
      ),
    )
    val flt = new Tensor(
      filter.map(_.map(_.flatten).flatten).flatten,
      Shape(2, 2, 4, 4)
    )

    val expected = Tensor.goldenConv(img, flt, BigInt(0))

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      m.dataIn(img, 0)
      m.weightsIn(flt, 0 + weightsOffset)
      // TODO zero the addresses in the accumulator that won't be overwritten by the first matmul
      m.tiledMatMulForConvolutionWithAccTiling(
        0,
        Shape(3, 3, 4),
        0 + weightsOffset,
        flt.shape,
        33 + weightsOffset,
        18,
        (2, 2)
      )
      m.noOp(5)
      val result: Tensor[BigInt] = m.dataOut(Shape(3, 3, 4), 18)
      assert(result == expected)
    }
  }

  it should "do a convolution" in {
    val gen            = SInt(16.W)
    val width          = 2
    val depth          = 18
    val memDepth       = 72
    val weightMemDepth = 36
    val weightsOffset  = 36
    val arch = Architecture.mkWithDefaults(
      arraySize = width,
      localDepth = memDepth,
      accumulatorDepth = depth
    )

    // 4 input channels, 4 output channels
    // 3x3 image
    // 2x2 convolution
    val image: Array[Array[Array[BigInt]]] = Array(
      Array(
        Array(0, 1, 2, 3),
        Array(4, 5, 6, 7),
        Array(8, 9, 10, 11),
      ),
      Array(
        Array(12, 13, 14, 15),
        Array(16, 17, 18, 19),
        Array(20, 21, 22, 23),
      ),
      Array(
        Array(24, 25, 26, 27),
        Array(28, 29, 30, 31),
        Array(32, 33, 34, 35),
      )
    )
    val img = new Tensor(image.map(_.flatten).flatten, Shape(3, 3, 4))
    val filter: Array[Array[Array[Array[BigInt]]]] = Array(
      Array(
        Array(
          Array(36, 37, 38, 39),
          Array(40, 41, 42, 43),
          Array(44, 45, 46, 47),
          Array(48, 49, 50, 51),
        ),
        Array(
          Array(52, 53, 54, 55),
          Array(56, 57, 58, 59),
          Array(60, 61, 62, 63),
          Array(64, 65, 66, 67),
        ),
      ),
      Array(
        Array(
          Array(68, 69, 70, 71),
          Array(72, 73, 74, 75),
          Array(76, 77, 78, 79),
          Array(80, 81, 82, 83),
        ),
        Array(
          Array(84, 85, 86, 87),
          Array(88, 89, 90, 91),
          Array(92, 93, 94, 95),
          Array(96, 97, 98, 99),
        ),
      ),
    )
    val flt = new Tensor(
      filter.map(_.map(_.flatten).flatten).flatten,
      Shape(2, 2, 4, 4)
    )

    val expected = Tensor.goldenConv(img, flt, BigInt(0))

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      m.dataIn(img, 0)
      m.weightsIn(flt, 0 + weightsOffset)
      // TODO zero the addresses in the accumulator that won't be overwritten by the first matmul
      m.tiledMatMulForConvolution(
        0,
        Shape(3, 3, 4),
        0 + weightsOffset,
        flt.shape,
        33 + weightsOffset,
        0,
        None,
        (0, 0),
        accumulate = false
      )
      m.tiledMatMulForConvolution(
        0,
        Shape(3, 3, 4),
        0 + weightsOffset,
        flt.shape,
        33 + weightsOffset,
        0,
        None,
        (0, 1),
        accumulate = true
      )
      m.tiledMatMulForConvolution(
        0,
        Shape(3, 3, 4),
        0 + weightsOffset,
        flt.shape,
        33 + weightsOffset,
        0,
        None,
        (1, 0),
        accumulate = true
      )
      m.tiledMatMulForConvolution(
        0,
        Shape(3, 3, 4),
        0 + weightsOffset,
        flt.shape,
        33 + weightsOffset,
        0,
        None,
        (1, 1),
        accumulate = true
      )
      m.dataMove((0 until 18).toArray, (18 until 36).toArray)
      val result: Tensor[BigInt] = m.dataOut(Shape(3, 3, 4), 18)
      assert(result == expected)
    }
  }

  it should "tile mat mul correctly" in {
    val gen            = SInt(16.W)
    val width          = 2
    val depth          = 16
    val memDepth       = 48
    val weightMemDepth = 16
    val weightsOffset  = 32
    val arch = Architecture.mkWithDefaults(
      arraySize = width,
      localDepth = memDepth,
      accumulatorDepth = depth
    )
    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      val a: Array[Array[BigInt]] = Array(
        Array(1, 2, 3, 4),
        Array(5, 6, 7, 8),
        Array(9, 10, 11, 12),
        Array(13, 14, 15, 16),
        Array(17, 18, 19, 20),
      )
      val A = new Tensor(a.flatten, Shape(5, 4))
      val b: Array[Array[BigInt]] = Array(
        Array(21, 22, 23, 24, 25),
        Array(26, 27, 28, 29, 30),
        Array(31, 32, 33, 34, 35),
        Array(36, 37, 38, 39, 40),
      )
      val B        = new Tensor(b.flatten, Shape(4, 5)).pad(0, Array((0, 0), (0, 1)))
      val e        = Tensor.goldenMatMatMul(A.to2D(), B.to2D())
      val expected = new Tensor(e.flatten, Shape(5, 6))
      m.dataIn(A, 0)
      m.weightsIn(B, 0 + weightsOffset)
      m.tiledMatMul(
        0,
        A.shape,
        0 + weightsOffset,
        B.shape,
        12 + weightsOffset,
        12
      )
      val result: Tensor[BigInt] = m.dataOut(Shape(5, 6), 12)
      assert(result == expected)
    }
  }

  it should "do a matmul with contiguous data" in {
    // A B   E F   AE+BG AF+BH
    // C D x G H = CE+DG CF+DH
    val gen            = SInt(16.W)
    val width          = 2
    val depth          = 8
    val memDepth       = 26
    val weightMemDepth = 10 // 8 for the matrix + 2 for the bias (zeroes)
    val weightsOffset  = 16
    val arch = Architecture.mkWithDefaults(
      arraySize = width,
      localDepth = memDepth,
      accumulatorDepth = depth
    )

    val a: Array[Array[BigInt]] = Array(
      Array(1, 2, 3, 4),
      Array(5, 6, 7, 8),
      Array(9, 10, 11, 12),
      Array(13, 14, 15, 16),
    )
    val b: Array[Array[BigInt]] = Array(
      Array(17, 18, 19, 20),
      Array(21, 22, 23, 24),
      Array(25, 26, 27, 28),
      Array(29, 30, 31, 32),
    )
    val A        = new Tensor(a.flatten, Shape(4, 4))
    val B        = new Tensor(b.flatten, Shape(4, 4))
    val e        = Tensor.goldenMatMatMul(a, b)
    val expected = new Tensor(e.flatten, Shape(4, 4))

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      // val topLeft     = Index(Right(Slice(0, 2)), Right(Slice(0, 2)))
      // val topRight    = Index(Right(Slice(0, 2)), Right(Slice(2, 4)))
      // val bottomLeft  = Index(Right(Slice(2, 4)), Right(Slice(0, 2)))
      // val bottomRight = Index(Right(Slice(2, 4)), Right(Slice(2, 4)))

      // at the end, the following addresses will contain the result columns
      // 0, 1 -> topLeft
      // 2, 3 -> topRight
      // 4, 5 -> bottomLeft
      // 6, 7 -> bottomRight

      // load data into mem
      m.dataIn(A, 0)
      m.weightsIn(B, 0 + weightsOffset)

      // run matmul
      // loadWeights(B(topLeft))
      m.loadWeights(Array(8, 0, 2), weightsOffset)
      // A(topLeft)
      m.matMul(Array(0, 2), accumulate = false, Array(0, 2))
      // A(bottomLeft)
      m.matMul(Array(4, 6), accumulate = false, Array(4, 6))

      // loadWeights(B(topRight))
      m.loadWeights(Array(8, 1, 3), weightsOffset)
      // A(topLeft)
      m.matMul(Array(0, 2), accumulate = false, Array(1, 3))
      // A(bottomLeft)
      m.matMul(Array(4, 6), accumulate = false, Array(5, 7))

      // loadWeights(B(bottomLeft))
      m.loadWeights(Array(8, 4, 6), weightsOffset)
      // A(topRight)
      m.matMul(Array(1, 3), accumulate = true, Array(0, 2))
      // A(bottomRight)
      m.matMul(Array(5, 7), accumulate = true, Array(4, 6))

      // loadWeights(B(bottomRight))
      m.loadWeights(Array(8, 5, 7), weightsOffset)
      // A(topRight)
      m.matMul(Array(1, 3), accumulate = true, Array(1, 3))
      // A(bottomRight)
      m.matMul(Array(5, 7), accumulate = true, Array(5, 7))

      // move data from acc to mem
      m.dataMove(
        Array(0, 1, 2, 3, 4, 5, 6, 7),
        Array(8, 9, 10, 11, 12, 13, 14, 15)
      )

      // get data out of mem
      val output: Tensor[BigInt] = m.dataOut(Shape(4, 4), 8)

      assert(output == expected)

      // check that input was not modified inadvertently
      val input: Tensor[BigInt] = m.dataOut(Shape(4, 4), 0)
      assert(input == A)
    }
  }

  it should "do a matmul" in {
    // A B   E F   AE+BG AF+BH
    // C D x G H = CE+DG CF+DH
    val gen            = SInt(16.W)
    val width          = 2
    val depth          = 8
    val memDepth       = 26
    val weightMemDepth = 10 // 8 for the matrix + 2 for the bias (zeroes)
    val weightsOffset  = 16
    val arch = Architecture.mkWithDefaults(
      arraySize = width,
      localDepth = memDepth,
      accumulatorDepth = depth
    )

    val a: Array[Array[BigInt]] = Array(
      Array(1, 2, 3, 4),
      Array(5, 6, 7, 8),
      Array(9, 10, 11, 12),
      Array(13, 14, 15, 16),
    )
    val b: Array[Array[BigInt]] = Array(
      Array(17, 18, 19, 20),
      Array(21, 22, 23, 24),
      Array(25, 26, 27, 28),
      Array(29, 30, 31, 32),
    )
    val A        = new Tensor(a.flatten, Shape(4, 4))
    val B        = new Tensor(b.flatten, Shape(4, 4))
    val e        = Tensor.goldenMatMatMul(a, b)
    val expected = new Tensor(e.flatten, Shape(4, 4))

    test(
      new TCU(gen, arch)
    ) { m =>
      implicit val layout = m.setInstructionParameters()
      m.setClocks()

      val topLeft     = Index(Right(Slice(0, 2)), Right(Slice(0, 2)))
      val topRight    = Index(Right(Slice(0, 2)), Right(Slice(2, 4)))
      val bottomLeft  = Index(Right(Slice(2, 4)), Right(Slice(0, 2)))
      val bottomRight = Index(Right(Slice(2, 4)), Right(Slice(2, 4)))

      // at the end, the following addresses will contain the result columns
      // 0, 1 -> topLeft
      // 2, 3 -> topRight
      // 4, 5 -> bottomLeft
      // 6, 7 -> bottomRight

      // load data into mem
      m.dataIn(A(topLeft), Array(0, 1))
      m.dataIn(A(topRight), Array(2, 3))
      m.dataIn(A(bottomLeft), Array(4, 5))
      m.dataIn(A(bottomRight), Array(6, 7))

      m.weightsIn(B(topLeft), Array(0, 1), weightsOffset)
      m.weightsIn(B(topRight), Array(2, 3), weightsOffset)
      m.weightsIn(B(bottomLeft), Array(4, 5), weightsOffset)
      m.weightsIn(B(bottomRight), Array(6, 7), weightsOffset)

      // run matmul
      // loadWeights(B(topLeft))
      // address 8 in the weight memory holds zeroes for the bias
      m.loadWeights(Array(8, 0, 1), weightsOffset)
      // A(topLeft)
      m.matMul(Array(0, 1), accumulate = false, Array(0, 1))
      // A(bottomLeft)
      m.matMul(Array(4, 5), accumulate = false, Array(4, 5))

      // loadWeights(B(topRight))
      m.loadWeights(Array(8, 2, 3), weightsOffset)
      // A(topLeft)
      m.matMul(Array(0, 1), accumulate = false, Array(2, 3))
      // A(bottomLeft)
      m.matMul(Array(4, 5), accumulate = false, Array(6, 7))

      // loadWeights(B(bottomLeft))
      m.loadWeights(Array(8, 4, 5), weightsOffset)
      // A(topRight)
      m.matMul(Array(2, 3), accumulate = true, Array(0, 1))
      // A(bottomRight)
      m.matMul(Array(6, 7), accumulate = true, Array(4, 5))

      // loadWeights(B(bottomRight))
      m.loadWeights(Array(8, 6, 7), weightsOffset)
      // A(topRight)
      m.matMul(Array(2, 3), accumulate = true, Array(2, 3))
      // A(bottomRight)
      m.matMul(Array(6, 7), accumulate = true, Array(6, 7))

      // move data from acc to mem
      m.dataMove(
        Array(0, 1, 2, 3, 4, 5, 6, 7),
        Array(8, 9, 10, 11, 12, 13, 14, 15)
      )

      // get data out of mem
      val output: Tensor[BigInt] = m.dataOut(
        Shape(4, 4),
        Array(8, 9, 10, 11, 12, 13, 14, 15),
        Array(
          // address 8
          Array(
            (0, 0),
            (0, 1),
          ),
          // address 9
          Array(
            (1, 0),
            (1, 1),
          ),
          // address 10
          Array(
            (0, 2),
            (0, 3),
          ),
          // address 11
          Array(
            (1, 2),
            (1, 3),
          ),
          // address 12
          Array(
            (2, 0),
            (2, 1),
          ),
          // address 13
          Array(
            (3, 0),
            (3, 1),
          ),
          // address 14
          Array(
            (2, 2),
            (2, 3),
          ),
          // address 15
          Array(
            (3, 2),
            (3, 3),
          ),
        )
      )

      assert(output == expected)
    }
  }

  def arrayFilter[T : ClassTag](
      target: Seq[T],
      filter: Seq[Boolean]
  ): Array[T] = {
    target
      .zip(filter)
      .filter({ case (_, v) => v })
      .map({ case (a, _) => a })
      .toArray
  }

  implicit class TCUHelper[T <: Data with Num[T] : ClassTag](m: TCU[T]) {
    implicit val layout: InstructionLayout = m.layout
    implicit val arch: Architecture        = m.layout.arch

    def tpuExecutiveGoldenTest[S : Numeric : ClassTag](
        weights: Tensor[S],
        input: Tensor[S],
        expectedOutput: S,
        tolerance: S,
        weightsOffset: Int,
    ): Unit = {
      m.weightsInSingleInstruction(weights, 0 + weightsOffset)

      m.dataIn(input, 0)

      m.loadWeights(4 + weightsOffset, 2)
      m.loadWeights(3 + weightsOffset, 1) // load bias

      m.matMul(Array(0), accumulate = false, Array(0))

      m.noOp(2)

      m.activate(Array(0), Array(0))

      m.noOp(6)

      m.dataMove(1, 0, 1)

      m.noOp(3)

      m.loadWeights(1 + weightsOffset, 2)
      m.loadWeights(0 + weightsOffset, 1) // load bias

      m.matMul(Array(1), accumulate = false, Array(1))

      m.noOp(2)

      m.activate(Array(1), Array(1))

      m.noOp(6)

      m.dataMove(2, 1, 1)

      m.noOp(5)

      val output: Tensor[S] =
        m.dataOut(Shape(1, m.arch.arraySize), 2)
      val n = implicitly[Numeric[S]]
      assert(
        n.lteq(
          n.abs(n.minus(output.get(Array(0, 0)), expectedOutput)),
          n.abs(tolerance)
        )
      )
    }

    // scalastyle:off method.length
    def tiledMatMulForConvolutionWithAccTiling(
        dataBaseAddress: Int,
        dataShape: Shape,
        weightBaseAddress: Int,
        weightShape: Shape,
        zerosBaseAddress: Int,
        outputBaseAddress: Int,
        filterShape: (Int, Int)
    ): Unit = {
      val a           = new Tensor(Array.fill(dataShape.arraySize)(BigInt(0)), dataShape)
      val dataLength  = divCeil(a.shape.arraySize, m.arch.arraySize)
      val numAccTiles = divCeil(dataLength, m.arch.accumulatorDepth.toInt)
      if (numAccTiles <= 1) {
        for {
          i <- 0 until filterShape._1
          j <- 0 until filterShape._2
        } {
          val accumulate = !(i == 0 && j == 0)
          m.tiledMatMulForConvolution(
            dataBaseAddress,
            dataShape,
            weightBaseAddress,
            weightShape,
            zerosBaseAddress,
            0,
            None,
            (i, j),
            accumulate
          )
          m.dataMove(
            Range(0, m.arch.accumulatorDepth.toInt).toArray,
            Range(
              outputBaseAddress,
              outputBaseAddress + m.arch.accumulatorDepth.toInt
            ).toArray
          )
        }
      } else {
        for (accTile <- 0 until numAccTiles) {
          val rangeStart = accTile * m.arch.accumulatorDepth.toInt
          val rangeEnd =
            math.min(rangeStart + m.arch.accumulatorDepth.toInt, dataLength)
          val rangeLen = rangeEnd - rangeStart
          for {
            i <- 0 until filterShape._1
            j <- 0 until filterShape._2
          } {
            val accumulate = !(i == 0 && j == 0)
            m.tiledMatMulForConvolution(
              dataBaseAddress,
              dataShape,
              weightBaseAddress,
              weightShape,
              zerosBaseAddress,
              0,
              Some(rangeStart, rangeEnd),
              (i, j),
              accumulate
            )
          }
          m.dataMove(
            Range(0, rangeLen).toArray,
            Range(
              outputBaseAddress + rangeStart,
              outputBaseAddress + rangeEnd
            ).toArray
          )
        }
      }
    }
    // scalastyle:on method.length

    // scalastyle:off method.length parameter.number cyclomatic.complexity
    def tiledMatMulForConvolution(
        dataBaseAddress: Int,
        dataShape: Shape,
        weightBaseAddress: Int,
        weightShape: Shape,
        zerosBaseAddress: Int, // address of a row of zeros, stand in for bias
        resultAccBaseAddress: Int,
        accAddressFilterRange: Option[(Int, Int)],
        filterPixel: (Int, Int),
        accumulate: Boolean,
        debug: Boolean = false
    ): Unit = {
      // left is the image matrix in HxWxI form
      // right is the weight matrix which is in HxWxIxO form

      // filterPixel is the index of the filter pixel in the weight matrix. It is 2-dimensional.
      val (fi, fj) = filterPixel

      // a dummy tensor used for addressing into the input tensor
      val a = new Tensor(Array.fill(dataShape.arraySize)(BigInt(0)), dataShape)
      // the slice of the weight tensor corresponding to the current filter pixel
      val right =
        new Tensor(Array.fill(weightShape.arraySize)(BigInt(0)), weightShape)
      //    val b = right(Index(Left(fi), Left(fj), Right(Slice.all), Right(Slice.all)))
      //      .squash()
      // the padding required under TF "SAME" output shape
      val padding = Tensor.paddingForSame(weightShape(0), weightShape(1))
      // a dummy tensor used for addressing into the result tensor
      val result = Tensor.resultTensorForConvolution(
        dataShape,
        weightShape,
        padding,
      )

      // tiling parameters
      val N         = m.arch.arraySize // systolic array size
      val numJTiles = divCeil(a.shape(1), N)
      val numKTiles = divCeil(right.shape(3), N)

      // addressing offsets for the result tensor
      val (paddingTop, _)  = padding(0)
      val (paddingLeft, _) = padding(1)
      val pi               = paddingTop - fi
      val pj               = paddingLeft - fj

      for (k <- 0 until numKTiles) {
        // the indices at which to store the results of this matmul
        val accIdx = Index(
          Right(Slice(pi, a.shape(0) + pi)),
          Right(Slice(pj, a.shape(1) + pj)),
          Left(k * N),
        )
        // valid address is the set of indices for which all dimensions are
        // greater than 0. When the dimension is negative this means the index
        // identifies a pixel that is off the edge of the timage. This is used to
        // determine which indices correspond to valid data addresses and must be
        // matmul-ed, and which can be skipped.
        val validAddressP = new CartesianProduct(
          accIdx.asArrays(result.shape): _*
        )
        val validAddress = (for (idx <- validAddressP)
          yield idx.map(i => i >= 0).reduce(_ && _)).toArray

        // the addresses corresponding to the indices at which to store the
        // results of this matmul
        val accAddress = result.address(
          accIdx,
          base = resultAccBaseAddress,
          pageSize = N
        )
        // restrict to only the accumulators that are active (within the filter
        // range)
        val activeAddress = accAddressFilterRange match {
          case None =>
            validAddress
          case Some((low, high)) =>
            val activeFilter =
              accAddress.map(address => address >= low && address < high)
            validAddress.zip(activeFilter).map({ case (v, a) => v && a })
        }
        // the accumulator addresses filtered by valid && active
        val accAddressF = arrayFilter(accAddress, activeAddress)

        // wrap addresses into real acc address space
        val accAddressFM = accAddressF.map(_ % m.arch.accumulatorDepth.toInt)

        // don't loadWeights and perform matmul if there are no valid and active
        // addresses
        if (!accAddressFM.isEmpty) {
          for (j <- 0 until numJTiles) {
            // the weights tile to be loaded
            val weightAddress = right.address(
              Index(
                Left(fi),
                Left(fj),
                Right(Slice(j * N, (j + 1) * N)),
                Left(k * N)
              ),
              base = weightBaseAddress,
              pageSize = N
            )
            m.loadWeights(Array(zerosBaseAddress) ++ weightAddress, 0)
            // whether to overwrite contents of accumulators
            val overwrite = !accumulate && j == 0
            // the addresses for the input tile to be loaded
            val dataAddress = a.address(
              Index(Right(Slice.all), Right(Slice.all), Left(j * N)),
              base = dataBaseAddress,
              pageSize = N
            )
            // the filtered input tile data addresses
            val dataAddressF = arrayFilter(dataAddress, activeAddress)

            // debug print
            if (debug) {
              println(s"addresses for j=$j k=$k")
              println("dataAddressF = " + dataAddressF.mkString(","))
              println("accAddressF =" + accAddressF.mkString(","))
            }

            // perform the matmul
            m.matMul(dataAddressF, !overwrite, accAddressFM)
          }
        }
      }
    }

    // scalastyle:on method.length parameter.number cyclomatic.complexity

    def tiledMatMul(
        dataBaseAddress: Int,
        dataShape: Shape,
        weightBaseAddress: Int,
        weightShape: Shape,
        zerosBaseAddress: Int, // address of a row of zeros, stand in for bias for now
        resultBaseAddress: Int
    ): Unit = {
      val a = new Tensor(Array.fill(dataShape.arraySize)(0), dataShape)
      val b = new Tensor(Array.fill(weightShape.arraySize)(0), weightShape)
      val N = m.arch.arraySize // systolic array size
      val numITiles =
        a.shape(0) // we don't tile the i direction, we just stream it through
      val numJTiles = divCeil(a.shape(1), N)
      val numKTiles = divCeil(b.shape(1), N)
      val result = new Tensor(
        Array.fill(a.shape(0) * b.shape(1))(0),
        Shape(a.shape(0), b.shape(1))
      )
      for (k <- 0 until numKTiles) {
        val accAddress = Range(0, numITiles, 1).toArray
        for (j <- 0 until numJTiles) {
          val weightAddress = b.address(
            Index(Right(Slice(j * N, (j + 1) * N)), Left(k * N)),
            base = weightBaseAddress,
            pageSize = N
          )
          m.loadWeights(Array(zerosBaseAddress) ++ weightAddress, 0)
          val dataAddress = a.address(
            Index(Right(Slice(0, numITiles)), Left(j * N)),
            base = dataBaseAddress,
            pageSize = N
          )
          m.matMul(dataAddress, j != 0, accAddress)
        }
        val resultAddress = result.address(
          Index(Right(Slice(0, numITiles)), Left(k * N)),
          base = resultBaseAddress,
          pageSize = N
        )
        m.dataMove(accAddress, resultAddress)
      }
    }

    def loadWeights(
        address: Int,
        size: Int,
    ): Unit = {
      m.io.instruction.enqueue(
        Instruction(
          Opcode.LoadWeights,
          LoadWeightFlags(false),
          LoadWeightArgs(address, size - 1)
        )
      )
    }

    def loadWeightsZeroes(
        size: Int
    ): Unit = {
      m.io.instruction.enqueue(
        Instruction(
          Opcode.LoadWeights,
          LoadWeightFlags(true),
          LoadWeightArgs(0, size - 1)
        )
      )
    }

    def loadWeights(
        weightMemAddress: Array[Int],
        offset: Int
    ): Unit = {
      // first address in weightMemAddress must be the address of the biases
      if (weightMemAddress.length != m.arch.arraySize + 1) {
        throw new Exception(
          "must supply a weight memory address for every row of the systolic array"
        )
      }

      // load in reverse order so that the weights referred to by weightMemAddress(i)
      // will be stored in systolic array row i
      for (address <- weightMemAddress.reverse) {
        m.io.instruction.enqueue(
          Instruction(
            Opcode.LoadWeights,
            LoadWeightFlags(false),
            LoadWeightArgs(address + offset)
          )
        )
      }
    }

    def weightsInSingleInstruction[S : Numeric : ClassTag](
        tensor: Tensor[S],
        address: Int
    ): Unit = {
      val rowLength = m.io.dram1.dataIn.bits.length
      if (tensor.shape.arraySize % rowLength != 0) {
        throw new Exception(
          "tensor size is not divisible by memory vector width. Pad with zeros to make it match"
        )
      }
      val numRows = tensor.shape.arraySize / rowLength
      val mat     = tensor.reshape(Shape(numRows, rowLength)).to2D()

      val t = fork {
        m.io.dram1.control
          .expectDequeue(
            MemControl(m.arch.dram1Depth, address.U, (numRows - 1).U, false.B)
          )
        for (i <- 0 until numRows) {
          m.io.dram1.dataIn
            .enqueue(mat(i).map(Tensor.litAsChiselType(m.gen)(_)))
        }

      }

      m.io.instruction.enqueue(
        Instruction(
          Opcode.DataMove,
          DataMoveFlags(DataFlowControl.dram1ToMemory),
          DataMoveArgs(address, address, numRows - 1)
        )
      )

      t.join()
    }

    def weightsIn[S : Numeric : ClassTag](
        tensor: Tensor[S],
        address: Int
    ): Unit = {
      val rowLength = m.io.dram1.dataIn.bits.length
      if (tensor.shape.arraySize % rowLength != 0) {
        throw new Exception(
          "tensor size is not divisible by memory vector width. Pad with zeros to make it match"
        )
      }
      val numRows = tensor.shape.arraySize / rowLength
      val mat     = tensor.reshape(Shape(numRows, rowLength)).to2D()

      val t = fork {
        m.io.dram1.control
          .expectDequeue(
            MemControl(m.arch.dram1Depth, address.U, (numRows - 1).U, false.B)
          )
        for (i <- 0 until numRows) {
          m.io.dram1.dataIn
            .enqueue(mat(i).map(Tensor.litAsChiselType(m.gen)(_)))
        }
      }

      m.io.instruction.enqueue(
        Instruction(
          Opcode.DataMove,
          DataMoveFlags(DataFlowControl.dram1ToMemory),
          DataMoveArgs(address, address, numRows - 1)
        )
      )

      t.join()
    }

    def weightsIn[S : Numeric : ClassTag](
        mat: Array[Array[S]],
        address: Array[Int],
        offset: Int
    ): Unit = {
      if (mat.length != address.length) {
        throw new Exception("must supply an address for every input row")
      }

      val t = fork {
        for (i <- address.indices) {
          m.io.dram1.control
            .expectDequeue(
              MemControl(m.arch.dram1Depth)((address(i) + offset).U, false.B)
            )
          m.io.dram1.dataIn
            .enqueue(mat(i).map(Tensor.litAsChiselType(m.gen)(_)))
        }
      }

      for (i <- address.indices) {
        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.dram1ToMemory),
            DataMoveArgs(address(i) + offset, address(i) + offset, 0)
          )
        )
      }

      t.join()
    }

    def weightsIn[S : Numeric : ClassTag](
        tensor: Tensor[S],
        address: Array[Int],
        offset: Int
    ): Unit = {
      val rowLength = m.io.dram1.dataIn.bits.length
      if (tensor.shape.arraySize % rowLength != 0) {
        throw new Exception(
          "tensor size is not divisible by memory vector width. Pad with zeros to make it match"
        )
      }
      val numRows = tensor.shape.arraySize / rowLength
      m.weightsIn(
        tensor.reshape(Shape(numRows, rowLength)).to2D(),
        address,
        offset
      )
    }

    def matMul(
        memAddress: Int,
        accAddress: Int,
        size: Int,
        accumulate: Boolean,
        zeroes: Boolean
    ): Unit = {
      m.io.instruction.enqueue(
        Instruction(
          Opcode.MatMul,
          MatMulFlags(accumulate, zeroes),
          MatMulArgs(memAddress, accAddress, size - 1)
        )
      )
    }

    def matMul(
        memAddress: Array[Int],
        accumulate: Boolean,
        accAddress: Array[Int]
    ): Unit = {
      if (memAddress.length != accAddress.length) {
        throw new Exception(
          "must supply an address for every row vector in memory"
        )
      }

      for (k <- memAddress.indices) {
        m.io.instruction.enqueue(
          Instruction(
            Opcode.MatMul,
            MatMulFlags(accumulate),
            MatMulArgs(memAddress(k), accAddress(k))
          )
        )
      }
    }

    def dataIn[S : Numeric : ClassTag](
        mat: Array[Array[S]],
        address: Array[Int]
    ): Unit = {
      if (mat.length != address.length) {
        throw new Exception("must supply an address for every input row")
      }

      val t = fork {
        for (i <- address.indices) {
          m.io.dram0.control
            .expectDequeue(MemControl(m.arch.dram0Depth)(address(i).U, false.B))
          m.io.dram0.dataIn
            .enqueue(mat(i).map(Tensor.litAsChiselType(m.gen)(_)))
        }
      }

      for (i <- address.indices) {
        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.dram0ToMemory),
            DataMoveArgs(address(i), address(i), 0)
          )
        )
      }

      t.join()
    }

    def dataIn[S : Numeric : ClassTag](
        tensor: Tensor[S],
        address: Array[Int]
    ): Unit = {
      val rowLength = m.io.dram0.dataIn.bits.length
      if (tensor.shape.arraySize % rowLength != 0) {
        throw new Exception(
          "tensor size is not divisible by memory vector width. Pad with zeros to make it match"
        )
      }
      val numRows = tensor.shape.arraySize / rowLength
      dataIn(tensor.reshape(Shape(numRows, rowLength)).to2D(), address)
    }

    def dataIn[S : Numeric : ClassTag](
        tensor: Tensor[S],
        address: Int
    ): Unit = {
      val rowLength = m.io.dram0.dataIn.bits.length
      if (tensor.shape.arraySize % rowLength != 0) {
        throw new Exception(
          "tensor size is not divisible by memory vector width. Pad with zeros to make it match"
        )
      }
      val numRows = tensor.shape.arraySize / rowLength
      val mat     = tensor.reshape(Shape(numRows, rowLength)).to2D()

      val t = fork {
        for (i <- address until address + numRows) {
          m.io.dram0.control
            .expectDequeue(MemControl(m.arch.dram0Depth)(i.U, false.B))
          m.io.dram0.dataIn
            .enqueue(mat(i - address).map(Tensor.litAsChiselType(m.gen)(_)))
        }
      }

      for (i <- address until address + numRows) {
        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.dram0ToMemory),
            DataMoveArgs(i, i, 0)
          )
        )
      }

      t.join()
    }

    def dataInSingleInstruction[S : Numeric : ClassTag](
        tensor: Tensor[S],
        address: Int
    ): Unit = {
      val rowLength = m.io.dram0.dataIn.bits.length
      if (tensor.shape.arraySize % rowLength != 0) {
        throw new Exception(
          "tensor size is not divisible by memory vector width. Pad with zeros to make it match"
        )
      }
      val numRows = tensor.shape.arraySize / rowLength
      val mat     = tensor.reshape(Shape(numRows, rowLength)).to2D()

      val t = fork {
        m.io.dram0.control
          .expectDequeue(
            MemControl(m.dram0Depth, address.U, (numRows - 1).U, false.B)
          )
        for (i <- 0 until numRows) {
          m.io.dram0.dataIn
            .enqueue(mat(i).map(Tensor.litAsChiselType(m.gen)(_)))
        }
      }

      m.io.instruction.enqueue(
        Instruction(
          Opcode.DataMove,
          DataMoveFlags(DataFlowControl.dram0ToMemory),
          DataMoveArgs(address, address, numRows - 1)
        )
      )

      t.join()
    }

    def dataMove(
        accAddress: Array[Int],
        memAddress: Array[Int]
    ): Unit = {
      if (accAddress.length != memAddress.length) {
        throw new Exception(
          "must supply an accumulator address for every mem " +
            "address"
        )
      }

      for (i <- accAddress.indices) {
        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.accumulatorToMemory),
            DataMoveArgs(memAddress(i), accAddress(i))
          )
        )
      }
    }

    def dataMove(
        memAddress: Int,
        accAddress: Int,
        size: Int
    ): Unit = {
      m.io.instruction.enqueue(
        Instruction(
          Opcode.DataMove,
          DataMoveFlags(DataFlowControl.accumulatorToMemory),
          DataMoveArgs(memAddress, accAddress, size - 1)
        )
      )
    }

    def activate(
        readAddress: Array[Int],
        writeAddress: Array[Int]
    ): Unit = {
      if (readAddress.length != writeAddress.length) {
        throw new Exception(
          "must supply a write address for every read address"
        )
      }
      m.io.instruction.enqueue(
        Instruction(
          Opcode.SIMD,
          SIMDFlags(read = false, write = false, accumulate = false),
          SIMDArgs(
            0,
            0,
            simd.Instruction(
              simd.Op.Zero,
              0,
              0,
              1,
            )
          ),
        )
      )
      for (i <- readAddress.indices) {
        m.io.instruction.enqueue(
          Instruction(
            Opcode.SIMD,
            SIMDFlags(read = true, write = true, accumulate = false),
            SIMDArgs(
              readAddress(i),
              writeAddress(i),
              simd.Instruction(
                simd.Op.Max,
                0,
                1,
                0,
              )
            ),
          )
        )
      }
    }

    def dataOut[S : Numeric : ClassTag](
        shape: Shape,
        address: Int
    ): Tensor[S] = {
      val rowLength = m.io.dram0.dataOut.bits.length
      if (shape.arraySize % rowLength != 0) {
        throw new Exception("shape is not divisible by page size")
      }
      val length = shape.arraySize / rowLength

      val t = fork {
        for (i <- address until address + length) {
          m.io.dram0.control
            .expectDequeue(MemControl(m.arch.dram0Depth)(i.U, true.B))
        }
      }

      val forkResult = m.io.dram0.dataOut.dequeueSeq(length)
      for (i <- address until address + length) {
        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.memoryToDram0),
            DataMoveArgs(i, i, 0)
          )
        )
      }

      t.join()

      Tensor[S, T](forkResult.join().flatten, shape)
    }

    def dataOutNoInstruction[S : Numeric : ClassTag](
        shape: Shape,
        address: Int,
        enableSize: Boolean = true,
    ): Tensor[S] = {
      val rowLength = m.io.dram0.dataOut.bits.length
      if (shape.arraySize % rowLength != 0) {
        throw new Exception("shape is not divisible by page size")
      }
      val length = shape.arraySize / rowLength

      if (enableSize) {
        m.io.dram0.control
          .expectDequeue(
            MemControl(m.arch.dram0Depth, address.U, (length - 1).U, true.B)
          )
      } else {
        for (i <- 0 until length) {
          m.io.dram0.control
            .expectDequeue(
              MemControl(m.arch.dram0Depth)((address + i).U, true.B)
            )
        }
      }

      val forkResult = m.io.dram0.dataOut.dequeueSeq(length)
      Tensor[S, T](forkResult.join().flatten, shape)
    }

    def dataOutSingleInstruction[S : Numeric : ClassTag](
        shape: Shape,
        address: Int
    ): Tensor[S] = {
      val rowLength = m.io.dram0.dataOut.bits.length
      if (shape.arraySize % rowLength != 0) {
        throw new Exception("shape is not divisible by page size")
      }
      val length = shape.arraySize / rowLength

      val t = fork {
        m.io.dram0.control
          .expectDequeue(
            MemControl(m.arch.dram0Depth, address.U, (length - 1).U, true.B)
          )
      }

      val forkResult = m.io.dram0.dataOut.dequeueSeq(length)
      m.io.instruction.enqueue(
        Instruction(
          Opcode.DataMove,
          DataMoveFlags(DataFlowControl.memoryToDram0),
          DataMoveArgs(address, address, length - 1)
        )
      )
      t.join()
      Tensor[S, T](forkResult.join().flatten, shape)

    }

    def dataOut[S : Numeric : ClassTag](
        shape: Shape,
        address: Array[Int],
        index: Array[Array[(Int, Int)]]
    ): Tensor[S] = {
      for (i <- index) {
        if (i.length != m.io.dram0.dataOut.bits.length) {
          throw new Exception(
            "must supply an index for every element of the " +
              "memory's output vec"
          )
        }
      }
      val result = Tensor.ofChiselType[T, S](m.gen, shape)
      val forkResult =
        m.io.dram0.dataOut
          .dequeueSeq(divCeil(shape.arraySize, m.arch.arraySize))
      val t = fork {
        for (ki <- address.indices) {
          m.io.dram0.control
            .expectDequeue(MemControl(m.arch.dram0Depth)(address(ki).U, true.B))
        }
      }
      for (ki <- address.indices) {
        m.io.instruction.enqueue(
          Instruction(
            Opcode.DataMove,
            DataMoveFlags(DataFlowControl.memoryToDram0),
            DataMoveArgs(address(ki), address(ki), 0)
          )
        )
      }
      t.join()
      val resultValue = forkResult.join()
      for (ki <- address.indices) {
        for (kj <- index(ki).indices) {
          val (i, j) = index(ki)(kj)
          result.set(Array(i, j), resultValue(ki)(kj))
        }
      }
      result
    }

    def noOp(repeat: Int = 1): Unit = {
      for (_ <- 0 until repeat) {
        m.io.instruction.enqueue(Instruction(Opcode.NoOp))
      }
    }

    def setClocks(): Unit = {
      m.io.instruction.setSourceClock(m.clock)
      m.io.dram0.dataIn.setSourceClock(m.clock)
      m.io.dram0.dataOut.setSinkClock(m.clock)
      m.io.dram1.dataIn.setSourceClock(m.clock)
      m.io.dram0.control.setSinkClock(m.clock)
      m.io.dram1.control.setSinkClock(m.clock)
    }

    def setInstructionParameters(): InstructionLayout = {
      m.layout
    }
  }
}
