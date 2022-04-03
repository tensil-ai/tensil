/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import chisel3.experimental.BundleLiterals._
import chiseltest._
import tensil.FunUnitSpec
import tensil.tcu.instruction.{
  Configure,
  ConfigureArgs,
  DataMoveArgs,
  DataMoveFlags,
  MatMulArgs,
  MatMulFlags,
  Instruction,
  Opcode
}
import tensil.mem.MemControl
import tensil.InstructionLayout
import tensil.Architecture
import scala.collection.mutable
import chiseltest.internal.TesterThreadList
import tensil.tcu.instruction.LoadWeightFlags
import tensil.tcu.instruction.LoadWeightArgs
import tensil.tcu.instruction.DataMoveKind

class DecoderSpec extends FunUnitSpec {
  val options = TCUOptions(decoderTimeout = 10)

  describe("Decoder") {
    describe("when instruction width = 32 bits") {
      implicit val layout: InstructionLayout =
        new InstructionLayout(
          Architecture.mkWithDefaults(
            arraySize = 8,
            dram0Depth = 256,
            dram1Depth = 256,
            localDepth = 256,
            accumulatorDepth = 256,
            simdRegistersDepth = 1,
            stride0Depth = 1,
            stride1Depth = 1,
          )
        )
      implicit val arch: Architecture = layout.arch

      it("should raise tracepoint") {
        test(
          new Decoder(layout.arch, options)
        ) { m =>
          m.io.instruction.setSourceClock(m.clock)

          fork {
            m.io.instruction.enqueue(
              Instruction(
                Opcode.Configure,
                ConfigureArgs(Configure.tracepoint, 3)
              )
            )
            m.io.instruction.enqueue(
              Instruction(
                Opcode.Configure,
                ConfigureArgs(Configure.programCounter, 0)
              )
            )
            m.io.instruction.enqueue(Instruction(Opcode.NoOp))
            m.io.instruction.enqueue(Instruction(Opcode.NoOp))
            m.io.instruction.enqueue(Instruction(Opcode.NoOp))
          }

          m.io.tracepoint.expect(false.B)
          m.clock.step()
          m.io.tracepoint.expect(false.B)
          m.clock.step()
          m.io.tracepoint.expect(false.B)
          m.clock.step()
          m.io.tracepoint.expect(false.B)
          m.clock.step()
          m.io.tracepoint.expect(false.B)
          m.clock.step()
          m.io.tracepoint.expect(false.B)
          m.clock.step()
          m.io.tracepoint.expect(true.B)
        // m.clock.step()
        // m.io.tracepoint.expect(true.B)
        }

      }

      it("should raise timeout when stalled") {
        test(
          new Decoder(layout.arch, options)
        ) { m =>
          m.io.instruction.setSourceClock(m.clock)

          fork {
            m.io.instruction.enqueue(
              Instruction(
                Opcode.DataMove,
                DataMoveFlags(DataMoveKind.memoryToAccumulatorAccumulate),
                DataMoveArgs(0, 0, 0)
              )
            )
            m.io.instruction.enqueue(
              Instruction(
                Opcode.DataMove,
                DataMoveFlags(DataMoveKind.memoryToAccumulatorAccumulate),
                DataMoveArgs(0, 0, 0)
              )
            )
            m.io.instruction.enqueue(
              Instruction(
                Opcode.DataMove,
                DataMoveFlags(DataMoveKind.memoryToAccumulatorAccumulate),
                DataMoveArgs(0, 0, 0)
              )
            )
            m.io.instruction.enqueue(
              Instruction(
                Opcode.DataMove,
                DataMoveFlags(DataMoveKind.memoryToAccumulatorAccumulate),
                DataMoveArgs(0, 0, 0)
              )
            )
            m.io.instruction.enqueue(
              Instruction(
                Opcode.DataMove,
                DataMoveFlags(DataMoveKind.memoryToAccumulatorAccumulate),
                DataMoveArgs(0, 0, 0)
              )
            )
          }

          m.clock.step(20)
          m.io.timeout.expect(true.B)
        }
      }

      it("should set configuration registers") {
        test(
          new Decoder(layout.arch, options)
        ) { m =>
          m.io.instruction.setSourceClock(m.clock)

          m.io.instruction.enqueue(
            Instruction(
              Opcode.Configure,
              ConfigureArgs(Configure.dram0AddressOffset, 0x7b)
            )
          )
          m.clock.step()
          m.io.config.dram0AddressOffset.expect(0x7b0000.U)
          m.io.instruction.enqueue(
            Instruction(
              Opcode.Configure,
              ConfigureArgs(Configure.dram0CacheBehaviour, 3)
            )
          )
          m.clock.step()
          m.io.config.dram0CacheBehaviour.expect(3.U)

          m.io.instruction.enqueue(
            Instruction(
              Opcode.Configure,
              ConfigureArgs(Configure.dram1AddressOffset, 0x7b)
            )
          )
          m.clock.step()
          m.io.config.dram1AddressOffset.expect(0x7b0000.U)
          m.io.instruction.enqueue(
            Instruction(
              Opcode.Configure,
              ConfigureArgs(Configure.dram1CacheBehaviour, 3)
            )
          )
          m.clock.step()
          m.io.config.dram1CacheBehaviour.expect(3.U)
        }
      }

      describe("should move data from memory to accumulators") {
        for (accumulate <- Array(false, true)) {
          val flag =
            if (accumulate) DataMoveKind.memoryToAccumulatorAccumulate
            else DataMoveKind.memoryToAccumulator
          it(
            s"with accumulate = $accumulate"
          ) {
            test(
              new Decoder(layout.arch, options)
            ) { m =>
              m.io.instruction.setSourceClock(m.clock)
              m.io.dataflow.setSinkClock(m.clock)
              m.io.memPortA.setSinkClock(m.clock)
              m.io.acc.setSinkClock(m.clock)
              m.io.dram0.setSinkClock(m.clock)
              m.io.dram1.setSinkClock(m.clock)

              val covered = Array(false, false, false)
              fork {
                m.io.dataflow.expectDequeue(
                  DataFlowControlWithSize(m.arch.localDepth)(
                    DataFlowControl.memoryToAccumulator,
                    0.U
                  )
                )
                covered(0) = true
              }
              fork {
                m.io.memPortA.expectDequeue(
                  MemControl(layout.arch.localDepth)(0.U, false.B)
                )
                covered(1) = true
              }
              fork {
                m.io.acc.expectDequeue(
                  AccumulatorWithALUArrayControl.write(
                    0,
                    accumulate = accumulate
                  )
                )
                covered(2) = true
              }

              m.io.instruction.enqueue(
                Instruction(
                  Opcode.DataMove,
                  DataMoveFlags(flag),
                  DataMoveArgs(0, 0, 0)
                )
              )

              m.clock.step(20)
              covered.map(assert(_))
            }
          }
        }
      }

      it("should accept NoOps indefinitely") {
        test(
          new Decoder(layout.arch, options)
        ) { m =>
          m.io.instruction.setSourceClock(m.clock)

          for (i <- 0 until 1000) {
            m.io.instruction.enqueue(Instruction(Opcode.NoOp))
          }
        }
      }

      it(
        "should emit an address on mem.dataOut when given a dataOut instruction"
      ) {
        test(
          new Decoder(layout.arch, options)
        ) { m =>
          m.io.instruction.setSourceClock(m.clock)
          m.io.memPortB.setSinkClock(m.clock)
          m.io.hostDataflow.setSinkClock(m.clock)
          m.io.dram0.setSinkClock(m.clock)
          m.io.dram1.setSinkClock(m.clock)

          for (i <- 0 until 1000) {
            val thread0 = fork {
              m.io.hostDataflow.expectDequeue(
                HostDataFlowControl(HostDataFlowControl.Out0)
              )
            }
            val thread1 = fork {
              m.io.memPortB.expectDequeue(
                MemControl(layout.arch.localDepth)(
                  (i % layout.arch.localDepth).U,
                  false.B
                )
              )
            }
            val thread2 = fork {
              m.io.dram0.expectDequeue(
                MemControl(layout.arch.localDepth)(
                  (i % layout.arch.localDepth).U,
                  true.B
                )
              )
            }
            m.io.instruction
              .enqueue(
                Instruction(
                  Opcode.DataMove,
                  DataMoveFlags(DataMoveKind.memoryToDram0),
                  DataMoveArgs(
                    i % layout.arch.localDepth,
                    i % layout.arch.localDepth
                  )
                )
              )
            thread0.join()
            thread1.join()
            thread2.join()
          }
        }
      }

      it("should not stall when given an invalid instruction") {
        test(
          new Decoder(layout.arch, options)
        ) { m =>
          m.io.instruction.setSourceClock(m.clock)

          for (i <- 0 until 1000) {
            m.io.instruction.enqueue(Instruction(15.U))
          }
        }
      }

      it("should handle valid instructions after invalid instructions") {
        val memoryDepth = 256

        test(
          new Decoder(layout.arch, options)
        ) { m =>
          m.io.instruction.setSourceClock(m.clock)
          m.io.memPortB.setSinkClock(m.clock)
          m.io.hostDataflow.setSinkClock(m.clock)
          m.io.dram0.setSinkClock(m.clock)
          m.io.dram1.setSinkClock(m.clock)

          for (i <- 0 until 1000) {
            m.io.instruction.enqueue(Instruction(15.U))
          }

          for (i <- 0 until 1000) {
            m.io.instruction.enqueue(Instruction(Opcode.NoOp))
          }

          for (i <- 0 until 1000) {
            val thread0 = fork {
              m.io.hostDataflow.expectDequeue(
                HostDataFlowControl(HostDataFlowControl.Out0)
              )
            }
            val thread1 = fork {
              m.io.memPortB.expectDequeue(
                MemControl(layout.arch.localDepth)(
                  (i % layout.arch.localDepth).U,
                  false.B
                )
              )
            }
            val thread2 = fork {
              m.io.dram0.expectDequeue(
                MemControl(layout.arch.localDepth)(
                  (i % layout.arch.localDepth).U,
                  true.B
                )
              )
            }
            m.io.instruction
              .enqueue(
                Instruction(
                  Opcode.DataMove,
                  DataMoveFlags(DataMoveKind.memoryToDram0),
                  DataMoveArgs(
                    i % layout.arch.localDepth,
                    i % layout.arch.localDepth
                  )
                )
              )

            thread0.join()
            thread1.join()
            thread2.join()
          }
        }
      }

      it("should handle dataIn followed by dataOut") {
        val memoryDepth = 256

        test(
          new Decoder(layout.arch, options)
        ) { m =>
          m.io.instruction.setSourceClock(m.clock)
          m.io.memPortB.setSinkClock(m.clock)
          m.io.hostDataflow.setSinkClock(m.clock)
          m.io.dram0.setSinkClock(m.clock)
          m.io.dram1.setSinkClock(m.clock)

          val thread0 = fork {
            m.io.hostDataflow.expectDequeue(
              HostDataFlowControl(HostDataFlowControl.In0)
            )
            m.io.hostDataflow.expectDequeue(
              HostDataFlowControl(HostDataFlowControl.Out0)
            )
          }
          val thread1 = fork {
            m.io.memPortB.expectDequeue(MemControl(memoryDepth)(0.U, true.B))
            m.io.memPortB.expectDequeue(MemControl(memoryDepth)(0.U, false.B))
          }
          val thread2 = fork {
            m.io.dram0.expectDequeue(MemControl(memoryDepth)(0.U, false.B))
            m.io.dram0.expectDequeue(MemControl(memoryDepth)(0.U, true.B))
          }

          m.io.instruction.enqueue(
            Instruction(
              Opcode.DataMove,
              DataMoveFlags(DataMoveKind.dram0ToMemory),
              DataMoveArgs(0, 0, 0)
            )
          )
          m.io.instruction.enqueue(
            Instruction(
              Opcode.DataMove,
              DataMoveFlags(DataMoveKind.memoryToDram0),
              DataMoveArgs(0, 0, 0)
            )
          )

          thread0.join()
          thread1.join()
          thread2.join()
        }
      }
    }

    describe("when instruction width = 64 bits and stride*Depth = 8") {
      val arrayWidth = 8
      val accDepth   = 512
      val memDepth   = 2048
      implicit val layout: InstructionLayout =
        new InstructionLayout(
          Architecture.mkWithDefaults(
            arraySize = 8,
            dram0Depth = 1048576L,
            dram1Depth = 1048576L,
            localDepth = memDepth,
            accumulatorDepth = accDepth,
            simdRegistersDepth = 1,
            stride0Depth = 8,
            stride1Depth = 8
          )
        )
      implicit val arch: Architecture = layout.arch

      it("should handle strides") {
        test(new Decoder(layout.arch, options)) { m =>
          m.io.instruction.setSourceClock(m.clock)
          m.io.dataflow.setSinkClock(m.clock)
          m.io.hostDataflow.setSinkClock(m.clock)
          m.io.memPortA.setSinkClock(m.clock)
          m.io.memPortB.setSinkClock(m.clock)
          m.io.acc.setSinkClock(m.clock)
          m.io.dram0.setSinkClock(m.clock)
          m.io.dram1.setSinkClock(m.clock)
          m.io.array.setSinkClock(m.clock)

          val memAddress = 456
          val accAddress = 123
          val size       = 23
          val memStride  = 3
          val accStride  = 4
          val ms         = 1 << memStride
          val as         = 1 << accStride

          val threads = new mutable.ArrayBuffer[TesterThreadList]

          threads += fork {
            m.io.instruction.enqueue(
              Instruction(
                Opcode.MatMul,
                MatMulFlags(false, false),
                MatMulArgs(
                  memAddress,
                  accAddress,
                  size - 1,
                  memStride,
                  accStride
                )
              )
            )
            m.io.instruction.enqueue(
              Instruction(
                Opcode.LoadWeights,
                LoadWeightFlags(false),
                LoadWeightArgs(memAddress, size - 1, memStride)
              )
            )
            m.io.instruction.enqueue(
              Instruction(
                Opcode.DataMove,
                DataMoveFlags(DataMoveKind.dram0ToMemory),
                DataMoveArgs(
                  memAddress,
                  accAddress,
                  size - 1,
                  memStride,
                  accStride
                )
              )
            )
            m.io.instruction.enqueue(
              Instruction(
                Opcode.DataMove,
                DataMoveFlags(DataMoveKind.dram1ToMemory),
                DataMoveArgs(
                  memAddress,
                  accAddress,
                  size - 1,
                  memStride,
                  accStride
                )
              )
            )
          }

          // dram0
          threads += fork {
            // matmul
            // loadweights
            // datamove (dram0 -> mem)
            for (i <- 0 until size * as by as) {
              m.io.dram0.expectDequeue(
                MemControl(arch.dram0Depth)((accAddress + i).U, false.B)
              )
            }
            // datamove (dram1 -> mem)
          }

          // dram1
          threads += fork {
            // matmul
            // loadweights
            // datamove (dram0 -> mem)
            // datamove (dram1 -> mem)
            for (i <- 0 until size * as by as) {
              m.io.dram1.expectDequeue(
                MemControl(arch.dram1Depth)((accAddress + i).U, false.B)
              )
            }
          }

          // memPortA
          threads += fork {
            // matmul
            for (i <- 0 until size * ms by ms) {
              m.io.memPortA.expectDequeue(
                MemControl(arch.localDepth)((memAddress + i).U, false.B)
              )
            }
            // loadweights
            for (i <- (size - 1) * ms to 0 by -ms) {
              m.io.memPortA.expectDequeue(
                MemControl(arch.localDepth)((memAddress + i).U, false.B)
              )
            }
            // datamove (dram0 -> mem)
            // datamove (dram1 -> mem)
          }

          // memPortB
          threads += fork {
            // matmul
            // loadweights
            // datamove (dram0 -> mem)
            for (i <- 0 until size * ms by ms) {
              m.io.memPortB.expectDequeue(
                MemControl(arch.localDepth)((memAddress + i).U, true.B)
              )
            }
            // datamove (dram1 -> mem)
            for (i <- 0 until size * ms by ms) {
              m.io.memPortB.expectDequeue(
                MemControl(arch.localDepth)((memAddress + i).U, true.B)
              )
            }
          }

          // array
          threads += fork {
            // matmul
            for (i <- 0 until size) {
              m.io.array.expectDequeue(
                (new SystolicArrayControl).Lit(
                  _.load   -> false.B,
                  _.zeroes -> false.B
                )
              )
            }
            // loadweights
            for (i <- 0 until size) {
              m.io.array.expectDequeue(
                (new SystolicArrayControl).Lit(
                  _.load   -> true.B,
                  _.zeroes -> false.B
                )
              )
            }
            // datamove (dram0 -> mem)
            // datamove (dram1 -> mem)
          }

          // acc
          threads += fork {
            // matmul
            for (i <- 0 until size * as by as) {
              m.io.acc.expectDequeue(
                AccumulatorWithALUArrayControl(
                  simd.Instruction.noOp(),
                  0,
                  accAddress + i,
                  false,
                  true,
                  false
                )
              )
            }
            // loadweights
            // datamove (dram0 -> mem)
            // datamove (dram1 -> mem)
          }

          // dataflow
          threads += fork {
            // matmul
            m.io.dataflow.expectDequeue(
              DataFlowControlWithSize(m.arch.localDepth)(
                DataFlowControl._memoryToArrayToAcc,
                (size - 1).U
              )
            )
            // loadweights
            m.io.dataflow.expectDequeue(
              DataFlowControlWithSize(m.arch.localDepth)(
                DataFlowControl.memoryToArrayWeight,
                (size - 1).U
              )
            )
            // datamove (dram0 -> mem)
            // datamove (dram1 -> mem)
          }

          // host dataflow
          threads += fork {
            // datamove (dram0 -> mem)
            for (i <- 0 until size)
              m.io.hostDataflow.expectDequeue(
                HostDataFlowControl(HostDataFlowControl.In0)
              )
            // datamove (dram1 -> mem)
            for (i <- 0 until size)
              m.io.hostDataflow.expectDequeue(
                HostDataFlowControl(HostDataFlowControl.In1)
              )
          }

          threads.map(_.join())
        }
      }
    }

    describe("when instruction width = 64 bits") {
      val arrayWidth = 8
      val accDepth   = 512
      val memDepth   = 2048
      implicit val layout: InstructionLayout =
        new InstructionLayout(
          Architecture.mkWithDefaults(
            arraySize = 8,
            dram0Depth = 1048576L,
            dram1Depth = 1048576L,
            localDepth = memDepth,
            accumulatorDepth = accDepth,
            simdRegistersDepth = 1,
            stride0Depth = 1,
            stride1Depth = 1
          )
        )
      implicit val arch: Architecture = layout.arch

      it(
        s"should request high DRAM addresses correctly"
      ) {
        test(
          new Decoder(layout.arch, options)
        ) { m =>
          m.io.instruction.setSourceClock(m.clock)
          m.io.dataflow.setSinkClock(m.clock)
          m.io.hostDataflow.setSinkClock(m.clock)
          m.io.memPortA.setSinkClock(m.clock)
          m.io.memPortB.setSinkClock(m.clock)
          m.io.acc.setSinkClock(m.clock)
          m.io.dram0.setSinkClock(m.clock)
          m.io.dram1.setSinkClock(m.clock)
          m.io.array.setSinkClock(m.clock)

          {
            val t0 = fork {
              m.io.hostDataflow.expectDequeue(
                HostDataFlowControl(HostDataFlowControl.In0)
              )
            }
            val t1 = fork {
              m.io.dram0.expectDequeue(
                MemControl(layout.arch.dram0Depth.toInt)(0x0fffff.U, false.B)
              )
            }
            val t2 = fork {
              m.io.memPortB.expectDequeue(
                MemControl(layout.arch.localDepth.toInt)(0.U, true.B)
              )
            }
            m.io.instruction.enqueue(
              Instruction(
                Opcode.DataMove,
                DataMoveFlags(DataMoveKind.dram0ToMemory),
                DataMoveArgs(0, 0xfffff, 0x0)
              )
            )
            t0.join()
            t1.join()
            t2.join()
          }

          {
            val t0 = fork {
              m.io.hostDataflow.expectDequeue(
                HostDataFlowControl(HostDataFlowControl.Out0)
              )
            }
            val t1 = fork {
              m.io.dram0.expectDequeue(
                MemControl(layout.arch.dram0Depth.toInt)(0x0fffff.U, true.B)
              )
            }
            val t2 = fork {
              m.io.memPortB.expectDequeue(
                MemControl(layout.arch.localDepth.toInt)(0.U, false.B)
              )
            }
            m.io.instruction.enqueue(
              Instruction(
                Opcode.DataMove,
                DataMoveFlags(DataMoveKind.memoryToDram0),
                DataMoveArgs(0, 0xfffff, 0x0)
              )
            )
            t0.join()
            t1.join()
            t2.join()
          }

          {
            val t1 = fork {
              m.io.dram1.expectDequeue(
                MemControl(layout.arch.dram1Depth.toInt)(0x0fffff.U, false.B)
              )
            }
            val t2 = fork {
              m.io.memPortB.expectDequeue(
                MemControl(layout.arch.localDepth.toInt)(0.U, true.B)
              )
            }
            m.io.instruction.enqueue(
              Instruction(
                Opcode.DataMove,
                DataMoveFlags(DataMoveKind.dram1ToMemory),
                DataMoveArgs(0, 0xfffff, 0x0)
              )
            )
            t1.join()
            t2.join()
          }
        }
      }

      it(
        s"should work"
      ) {
        test(
          new Decoder(layout.arch, options)
        ) { m =>
          m.io.instruction.setSourceClock(m.clock)
          m.io.dataflow.setSinkClock(m.clock)
          m.io.memPortA.setSinkClock(m.clock)
          m.io.acc.setSinkClock(m.clock)
          m.io.dram0.setSinkClock(m.clock)
          m.io.dram1.setSinkClock(m.clock)
          m.io.array.setSinkClock(m.clock)

          val t0 = fork {
            m.io.dataflow.expectDequeue(
              DataFlowControlWithSize(m.arch.localDepth)(
                DataFlowControl._memoryToArrayToAcc,
                0.U
              )
            )
          }
          val t1 = fork {
            m.clock.step(100)
            m.io.memPortA.ready.poke(true.B)
            m.clock.step(100)
          }
          val t2 = fork {
            m.clock.step(100)
            m.io.array.ready.poke(true.B)
            m.clock.step(100)
          }
          val t3 = fork {
            m.clock.step(100)
            m.io.acc.ready.poke(true.B)
            m.clock.step(100)
          }

          m.io.instruction.enqueue(
            Instruction(
              Opcode.MatMul,
              MatMulFlags(false),
              MatMulArgs(
                0x164,
                0x20,
                0x0,
              )
            )
          )

          t1.join()
          t2.join()
          t3.join()
          t0.join()

        }
      }
    }
  }
}
