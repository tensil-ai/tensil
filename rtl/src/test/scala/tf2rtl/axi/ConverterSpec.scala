package tensil.axi

import chisel3._
import chisel3.experimental.FixedPoint
import chiseltest._
import tensil.FunUnitSpec
import tensil.decoupled.decoupledVecToDriver
import tensil.mem.MemControl

class ConverterSpec extends FunUnitSpec {
  describe("Converter") {
    describe(
      "when config=Xilinx, gen=FP(18.W, 10.BP), vectorSize=8, vectorMemDepth=4096"
    ) {
      implicit val config: Config = Config.Xilinx
      val gen                     = FixedPoint(18.W, 10.BP)
      val bytesPerScalar          = config.dataWidth / 8
      val vectorSize              = 8
      val vectorMemDepth          = 4096

      it("should move data from host to fabric") {
        test(new Converter(config, gen, vectorSize, vectorMemDepth)) { m =>
          m.setClocks()
          m.io.mem.dataIn.setSinkClock(m.clock)

          m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, false.B))
          m.io.axi.readAddress.expectDequeue(Address(0, vectorSize - 1))
          for (i <- 0 until vectorSize) {
            m.io.axi.readData
              .enqueue(
                ReadData(i << gen.binaryPoint.get, i == (vectorSize - 1))
              )
          }
          m.io.mem.dataIn.expectDequeue(
            (0 until vectorSize).map(_.F(gen.getWidth.W, gen.binaryPoint))
          )
        }
      }

      it("should move data from fabric to host") {
        test(new Converter(config, gen, vectorSize, vectorMemDepth)) { m =>
          m.setClocks()
          m.io.mem.dataOut.setSourceClock(m.clock)

          m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, true.B))
          m.io.axi.writeAddress.expectDequeue(Address(0, vectorSize - 1))
          m.io.mem.dataOut.enqueue(
            (0 until vectorSize).map(_.F(gen.getWidth.W, gen.binaryPoint))
          )
          for (i <- 0 until vectorSize) {
            m.io.axi.writeData.expectDequeue(
              WriteData(i << gen.binaryPoint.get, i == (vectorSize - 1))
            )
            m.io.axi.writeData.bits.strb.expect(0xf.U)
          }
          m.io.axi.writeResponse.enqueue(WriteResponse())
        }
      }

      it("should translate addresses to DRAM offset") {
        test(new Converter(config, gen, vectorSize, vectorMemDepth)) { m =>
          m.setClocks()

          val addr = 1

          m.io.mem.control.enqueue(MemControl(vectorMemDepth)(addr.U, false.B))
          m.io.axi.readAddress
            .expectDequeue(
              Address(addr * vectorSize * bytesPerScalar, vectorSize - 1)
            )
        }
      }

      it(
        "should wait for response before continuing with new request of different kind"
      ) {
        test(new Converter(config, gen, vectorSize, vectorMemDepth)) { m =>
          m.setClocks()
          m.io.mem.dataIn.setSinkClock(m.clock)
          m.io.mem.dataOut.setSourceClock(m.clock)

          // request write
          m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, true.B))
          m.io.axi.writeAddress.expectDequeue(Address(0, vectorSize - 1))
          m.io.mem.dataOut.enqueue(
            (0 until vectorSize).map(_.F(gen.getWidth.W, gen.binaryPoint))
          )
          for (i <- 0 until vectorSize) {
            m.io.axi.writeData.expectDequeue(
              WriteData(i << gen.binaryPoint.get, i == (vectorSize - 1))
            )
            m.io.axi.writeData.bits.strb.expect(0xf.U)
          }
          // request read
          m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, false.B))
          for (_ <- 0 until 50) {
            m.io.axi.readAddress.valid.expect(false.B)
          }
          // respond to write
          m.io.axi.writeResponse.enqueue(WriteResponse())
          // observe read request
          m.io.axi.readAddress.expectDequeue(Address(0, vectorSize - 1))
          // request write again
          m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, true.B))
          for (_ <- 0 until 50) {
            m.io.axi.writeAddress.valid.expect(false.B)
          }
          // respond to read
          for (i <- 0 until vectorSize) {
            m.io.axi.readData
              .enqueue(
                ReadData(i << gen.binaryPoint.get, i == (vectorSize - 1))
              )
          }
          m.io.mem.dataIn.expectDequeue(
            (0 until vectorSize).map(_.F(gen.getWidth.W, gen.binaryPoint))
          )
          // observe write request
          m.io.axi.writeAddress.expectDequeue(Address(0, vectorSize - 1))
        }
      }

      it("should allow numerous inflight requests of same kind") {
        val numInflightRequests = 8
        decoupledTest(
          new Converter(
            config,
            gen,
            vectorSize,
            vectorMemDepth,
            numInflightRequests = numInflightRequests
          )
        ) { m =>
          m.setClocks()
          m.io.mem.dataIn.setSinkClock(m.clock)
          m.io.mem.dataOut.setSourceClock(m.clock)

          // request writes
          for (j <- 0 until numInflightRequests - 1) {
            thread("mem.control") {
              m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, true.B))
            }
            thread("axi.writeAddress") {
              m.io.axi.writeAddress.expectDequeue(Address(0, vectorSize - 1))
            }
            thread("mem.dataOut") {
              m.io.mem.dataOut.enqueue(
                (0 until vectorSize).map(_.F(gen.getWidth.W, gen.binaryPoint))
              )
            }
            thread("axi.writeData") {
              for (i <- 0 until vectorSize) {
                m.io.axi.writeData.expectDequeue(
                  WriteData(i << gen.binaryPoint.get, i == (vectorSize - 1))
                )
                m.io.axi.writeData.bits.strb.expect(0xf.U)
              }
            }
          }

          // request read
          thread("mem.control") {
            m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, false.B))
          }
          thread("axi.readAddress") {
            for (_ <- 0 until 50) {
              m.io.axi.readAddress.valid.expect(false.B)
            }
          }
          // respond to writes
          for (i <- 0 until numInflightRequests - 1) {
            thread("axi.readAddress") {
              m.io.axi.readAddress.valid.expect(false.B)
            }
            thread("axi.writeResponse") {
              m.clock.step(100)
              m.io.axi.writeResponse.enqueue(WriteResponse())
            }
          }
          // observe read request
          thread("axi.readAddress") {
            m.io.axi.readAddress.expectDequeue(Address(0, vectorSize - 1))
          }
        }
      }

      it(
        "should only increase response count when address is both ready and valid"
      ) {
        val numInflightRequests = 8
        decoupledTest(
          new Converter(
            config,
            gen,
            vectorSize,
            vectorMemDepth,
            numInflightRequests = numInflightRequests
          )
        ) { m =>
          m.setClocks()
          m.io.mem.dataIn.setSinkClock(m.clock)
          m.io.mem.dataOut.setSourceClock(m.clock)

          thread("mem.control") {
            m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, true.B))
          }
          thread("axi.writeAddress") {
            m.io.axi.writeAddress.ready.poke(false.B)
            m.clock.step(numInflightRequests)
            m.io.axi.writeAddress.expectDequeue(Address(0, vectorSize - 1))
          }
          thread("mem.dataOut") {
            m.io.mem.dataOut.enqueue(
              Array.fill(vectorSize)(0.F(gen.getWidth.W, gen.binaryPoint))
            )
          }
          thread("axi.writeData") {
            for (i <- 0 until vectorSize) {
              m.io.axi.writeData.expectDequeue(
                WriteData(0, i == vectorSize - 1)
              )
            }
          }
          thread("mem.control") {
            m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, false.B))
          }
          thread("axi.writeResponse") {
            m.clock.step(20)
            m.io.axi.writeResponse.enqueue(WriteResponse())
          }
          thread("axi.readAddress") {
            m.io.axi.readAddress.expectDequeue(Address(0, vectorSize - 1))
          }
        }

        test(
          new Converter(
            config,
            gen,
            vectorSize,
            vectorMemDepth,
            numInflightRequests = numInflightRequests
          )
        ) { m =>
          m.setClocks()
          m.io.mem.dataIn.setSinkClock(m.clock)
          m.io.mem.dataOut.setSourceClock(m.clock)

          val t = fork {
            m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, false.B))
          }
          m.io.axi.readAddress.ready.poke(false.B)
          m.clock.step(numInflightRequests)
          m.io.axi.readAddress.expectDequeue(Address(0, vectorSize - 1))
          m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, true.B))
          m.io.axi.readData.enqueue(ReadData(0.U, true.B))
          m.io.axi.writeAddress.expectDequeue(Address(0, vectorSize - 1))
        }
      }

      it("should handle size correctly on read") {
        test(new Converter(config, gen, vectorSize, vectorMemDepth)) { m =>
          m.setClocks()
          m.io.mem.dataIn.setSinkClock(m.clock)

          val size = 8

          m.io.mem.control
            .enqueue(MemControl(vectorMemDepth, 0.U, (size - 1).U, false.B))
          m.io.axi.readAddress
            .expectDequeue(Address(0, (vectorSize * size) - 1))
          val t0 = fork {
            for (i <- 0 until vectorSize * size) {
              m.io.axi.readData
                .enqueue(
                  ReadData(i << gen.binaryPoint.get, i == (vectorSize - 1))
                )
            }
          }
          val t1 = fork {
            for (i <- 0 until size) {
              m.io.mem.dataIn.expectDequeue(
                (i * vectorSize until (i + 1) * vectorSize).map(
                  _.F(gen.getWidth.W, gen.binaryPoint)
                )
              )
            }
          }
          t0.join()
          t1.join()
        }
      }

      it("should handle size correctly on write") {
        test(new Converter(config, gen, vectorSize, vectorMemDepth)) { m =>
          m.setClocks()
          m.io.mem.dataOut.setSourceClock(m.clock)

          val size = 8

          m.io.mem.control
            .enqueue(MemControl(vectorMemDepth, 0.U, (size - 1).U, true.B))
          m.io.axi.writeAddress
            .expectDequeue(Address(0, (vectorSize * size) - 1))
          val t0 = fork {
            for (i <- 0 until size) {
              m.io.mem.dataOut.enqueue(
                (i * vectorSize until (i + 1) * vectorSize)
                  .map(_.F(gen.getWidth.W, gen.binaryPoint))
              )
            }
          }
          val t1 = fork {
            for (i <- 0 until vectorSize * size) {
              m.io.axi.writeData.expectDequeue(
                WriteData(
                  i << gen.binaryPoint.get,
                  i == (vectorSize * size - 1)
                )
              )
              m.io.axi.writeData.bits.strb.expect(0xf.U)
            }
          }
          m.io.axi.writeResponse.enqueue(WriteResponse())
          t0.join()
          t1.join()
        }
      }

      it("should split up requests that will require more than 256 beats") {
        test(new Converter(config, gen, vectorSize, vectorMemDepth)) { m =>
          m.setClocks()

          val size = 1024

          m.io.mem.control
            .enqueue(MemControl(vectorMemDepth, 0.U, (size - 1).U, false.B))
          for (i <- 0 until size * vectorSize / 256) {
            m.io.axi.readAddress
              .expectDequeue(Address(i * 256 * config.dataWidth / 8, 255))
          }
          m.io.axi.readAddress.valid.expect(false.B)
        }
      }
    }

    describe(
      "when config=Xilinx, gen=UInt(32.W), vectorSize=8, vectorMemDepth=4096"
    ) {
      implicit val config: Config = Config.Xilinx
      val gen                     = UInt(32.W)
      val bytesPerScalar          = config.dataWidth / 8
      val vectorSize              = 8
      val vectorMemDepth          = 4096

      it("should handle last correctly when splitting write requests") {
        test(new Converter(config, gen, vectorSize, vectorMemDepth)) { m =>
          m.setClocks()
          m.io.mem.dataOut.setSourceClock(m.clock)

          val size              = 1024
          val numRequests       = size * vectorSize / 256
          val vectorsPerRequest = 256 / vectorSize

          m.io.mem.control
            .enqueue(MemControl(vectorMemDepth, 0.U, (size - 1).U, true.B))
          val t0 = fork {
            for (i <- 0 until numRequests) {
              m.io.axi.writeAddress
                .expectDequeue(Address(i * 256 * config.dataWidth / 8, 255))
              for (i <- 0 until vectorsPerRequest) {
                m.io.mem.dataOut.enqueue(
                  (i * vectorSize until (i + 1) * vectorSize)
                    .map(_.U(gen.getWidth.W))
                )
              }
            }
          }
          val t1 = fork {
            for (i <- 0 until size * vectorSize) {
              m.io.axi.writeData.expectDequeue(
                WriteData(
                  i % 256,
                  i % 256 == 255
                )
              )
              m.io.axi.writeData.bits.strb.expect(0xf.U)
            }
          }
          m.io.axi.writeResponse.enqueue(WriteResponse())
          t0.join()
          t1.join()
        }
      }
    }

    describe(
      "when config=Xilinx, gen=FP(16.W, 8.BP), vectorSize=8, vectorMemDepth=4096"
    ) {
      implicit val config: Config = Config.Xilinx
      val gen                     = FixedPoint(16.W, 8.BP)
      val bytesPerScalar          = config.dataWidth / 8
      val vectorSize              = 8
      val vectorMemDepth          = 4096

      it("should move data from host to fabric") {
        test(new Converter(config, gen, vectorSize, vectorMemDepth)) { m =>
          m.setClocks()
          m.io.mem.dataIn.setSinkClock(m.clock)

          m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, false.B))
          m.io.axi.readAddress.expectDequeue(Address(0, vectorSize - 1))
          for (i <- 0 until vectorSize) {
            m.io.axi.readData
              .enqueue(
                ReadData(i << gen.binaryPoint.get, i == (vectorSize - 1))
              )
          }
          m.io.mem.dataIn.expectDequeue(
            (0 until vectorSize).map(_.F(gen.getWidth.W, gen.binaryPoint))
          )
        }
      }

      it("should move data from fabric to host") {
        test(new Converter(config, gen, vectorSize, vectorMemDepth)) { m =>
          m.setClocks()
          m.io.mem.dataOut.setSourceClock(m.clock)

          m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, true.B))
          m.io.axi.writeAddress.expectDequeue(Address(0, vectorSize - 1))
          m.io.mem.dataOut.enqueue(
            (0 until vectorSize).map(_.F(gen.getWidth.W, gen.binaryPoint))
          )
          for (i <- 0 until vectorSize) {
            m.io.axi.writeData.expectDequeue(
              WriteData(i << gen.binaryPoint.get, i == (vectorSize - 1))
            )
            m.io.axi.writeData.bits.strb.expect(0xf.U)
          }
          m.io.axi.writeResponse.enqueue(WriteResponse())
        }
      }

      it("should translate addresses to DRAM offset") {
        test(new Converter(config, gen, vectorSize, vectorMemDepth)) { m =>
          m.setClocks()

          val addr = 1

          m.io.mem.control.enqueue(MemControl(vectorMemDepth)(addr.U, false.B))
          m.io.axi.readAddress
            .expectDequeue(
              Address(addr * vectorSize * bytesPerScalar, vectorSize - 1)
            )
        }
      }
    }

    describe(
      "when config=Xilinx64, gen=FP(18.W, 10.BP), vectorSize=8, vectorMemDepth=4096"
    ) {
      implicit val config: Config = Config.Xilinx64
      val gen                     = FixedPoint(18.W, 10.BP)
      val bytesPerScalar          = config.dataWidth / 8
      val vectorSize              = 8
      val vectorMemDepth          = 4096
      val numScalarsPerWord       = 2

      it("should move data from host to fabric") {
        test(
          new Converter(
            config,
            gen,
            vectorSize,
            vectorMemDepth,
            numScalarsPerWord = numScalarsPerWord
          )
        ) { m =>
          m.setClocks()
          m.io.mem.dataIn.setSinkClock(m.clock)

          m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, false.B))
          m.io.axi.readAddress
            .expectDequeue(Address(0, (vectorSize / numScalarsPerWord) - 1))
          val bp = gen.binaryPoint.get
          for (i <- 0 until vectorSize / numScalarsPerWord) {
            val data = (((2 * i.toLong + 1) << bp) << 32) | (2 * i.toLong << bp)
            m.io.axi.readData
              .enqueue(
                ReadData(data, i == (vectorSize / numScalarsPerWord - 1))
              )
          }
          m.io.mem.dataIn.expectDequeue(
            (0 until vectorSize).map(_.F(gen.getWidth.W, gen.binaryPoint))
          )
        }
      }

      it("should move data from fabric to host") {
        test(
          new Converter(
            config,
            gen,
            vectorSize,
            vectorMemDepth,
            numScalarsPerWord = numScalarsPerWord
          )
        ) { m =>
          m.setClocks()
          m.io.mem.dataOut.setSourceClock(m.clock)

          m.io.mem.control.enqueue(MemControl(vectorMemDepth)(0.U, true.B))
          m.io.axi.writeAddress
            .expectDequeue(Address(0, (vectorSize / numScalarsPerWord) - 1))
          m.io.mem.dataOut.enqueue(
            (0 until vectorSize).map(_.F(gen.getWidth.W, gen.binaryPoint))
          )
          val bp = gen.binaryPoint.get
          for (i <- 0 until vectorSize / numScalarsPerWord) {
            val data = (((2 * i.toLong + 1) << bp) << 32) | (2 * i.toLong << bp)
            m.io.axi.writeData.expectDequeue(
              WriteData(data, i == (vectorSize / numScalarsPerWord - 1))
            )
            m.io.axi.writeData.bits.strb.expect(0xff.U)
          }
          m.io.axi.writeResponse.enqueue(WriteResponse())
        }
      }
    }
  }

  implicit class ConverterHelper[T <: Data](m: Converter[T]) {
    def setClocks(): Unit = {
      m.io.mem.control.setSourceClock(m.clock)
      m.io.mem.dataIn.setSinkClock(m.clock)
      m.io.mem.dataOut.setSourceClock(m.clock)
      m.io.axi.writeAddress.setSinkClock(m.clock)
      m.io.axi.writeData.setSinkClock(m.clock)
      m.io.axi.writeResponse.setSourceClock(m.clock)
      m.io.axi.readAddress.setSinkClock(m.clock)
      m.io.axi.readData.setSourceClock(m.clock)
    }
  }
}
