package tensil.axi

import chisel3._
import chiseltest._
import tensil.FunUnitSpec

class MemBoundarySplitterSpec extends FunUnitSpec {
  describe("MemBoundarySplitter") {
    val boundary = 1 << 12 // 4096
    val maxLen   = 1 << 8  // 256

    case class Test(addr: Int, len: Int, message: String)

    def nextBoundary(addr: Int): Int = addr + (boundary - (addr % boundary))

    def min(a: Int, b: Int): Int = if (a > b) b else a

    for (
      (configName, config) <- Map(
        "Xilinx"    -> Config.Xilinx,
        "Xilinx64"  -> Config.Xilinx64,
        "Xilinx128" -> Config.Xilinx128,
        "Xilinx256" -> Config.Xilinx256
      )
    ) {

      describe(s"when config = $configName") {
        implicit val cfg = config
        val bytesPerWord = config.dataWidth / 8

        describe("should pass through when") {

          def passThroughReadTest(tc: Test): Unit = {
            it("read " + tc.message) {
              decoupledTest(
                new MemBoundarySplitter(config, boundary, maxLen)
              ) { m =>
                m.io.in.readAddress.setSourceClock(m.clock)
                m.io.in.readData.setSinkClock(m.clock)
                m.io.in.writeAddress.setSourceClock(m.clock)
                m.io.in.writeData.setSourceClock(m.clock)
                m.io.in.writeResponse.setSinkClock(m.clock)

                m.io.out.readAddress.setSinkClock(m.clock)
                m.io.out.readData.setSourceClock(m.clock)
                m.io.out.writeAddress.setSinkClock(m.clock)
                m.io.out.writeData.setSinkClock(m.clock)
                m.io.out.writeResponse.setSourceClock(m.clock)

                val addr = tc.addr
                val len  = tc.len

                thread("in.readAddress") {
                  m.io.in.readAddress.enqueue(Address(addr, len - 1))
                }

                thread("in.readData") {
                  for (i <- 0 until len) {
                    m.io.in.readData
                      .expectDequeue(ReadData(i.U, (i == (len - 1)).B))
                  }
                }

                thread("out.readAddress") {
                  m.io.out.readAddress.expectDequeue(Address(addr, len - 1))
                }

                thread("out.readData") {
                  for (i <- 0 until len) {
                    m.io.out.readData.enqueue(ReadData(i.U, (i == (len - 1)).B))
                  }
                }
              }
            }
          }

          def passThroughWriteTest(tc: Test): Unit = {
            it("write " + tc.message) {
              decoupledTest(
                new MemBoundarySplitter(config, boundary, maxLen)
              ) { m =>
                m.io.in.readAddress.setSourceClock(m.clock)
                m.io.in.readData.setSinkClock(m.clock)
                m.io.in.writeAddress.setSourceClock(m.clock)
                m.io.in.writeData.setSourceClock(m.clock)
                m.io.in.writeResponse.setSinkClock(m.clock)

                m.io.out.readAddress.setSinkClock(m.clock)
                m.io.out.readData.setSourceClock(m.clock)
                m.io.out.writeAddress.setSinkClock(m.clock)
                m.io.out.writeData.setSinkClock(m.clock)
                m.io.out.writeResponse.setSourceClock(m.clock)

                val addr = tc.addr
                val len  = tc.len

                thread("in.writeAddress") {
                  m.io.in.writeAddress.enqueue(Address(addr, len - 1))
                }

                thread("out.writeData") {
                  for (i <- 0 until len) {
                    m.io.out.writeData
                      .expectDequeue(WriteData(i.U, (i == (len - 1)).B))
                  }
                }

                thread("out.writeAddress") {
                  m.io.out.writeAddress.expectDequeue(Address(addr, len - 1))
                }

                thread("in.writeData") {
                  for (i <- 0 until len) {
                    m.io.in.writeData
                      .enqueue(WriteData(i.U, (i == (len - 1)).B))
                  }
                }

                thread("out.writeResponse") {
                  m.io.out.writeResponse.enqueue(WriteResponse())
                }

                thread("in.writeResponse") {
                  m.io.in.writeResponse.expectDequeue(WriteResponse())
                }
              }
            }
          }

          val cases = Array(
            Test(
              0,
              2,
              "request does not cross a boundary"
            ),
            Test(
              boundary - 2 * bytesPerWord,
              2,
              "request ends on last address before a boundary"
            ),
            Test(
              boundary,
              2,
              "request starts on a boundary"
            )
          )

          for (test <- cases) {
            passThroughReadTest(test)
            passThroughWriteTest(test)
          }
        }

        describe("should split up when") {

          def splitUpReadTest(tc: Test): Unit = {
            it("read " + tc.message) {
              decoupledTest(
                new MemBoundarySplitter(config, boundary, maxLen)
              ) { m =>
                m.io.in.readAddress.setSourceClock(m.clock)
                m.io.in.readData.setSinkClock(m.clock)
                m.io.in.writeAddress.setSourceClock(m.clock)
                m.io.in.writeData.setSourceClock(m.clock)
                m.io.in.writeResponse.setSinkClock(m.clock)

                m.io.out.readAddress.setSinkClock(m.clock)
                m.io.out.readData.setSourceClock(m.clock)
                m.io.out.writeAddress.setSinkClock(m.clock)
                m.io.out.writeData.setSinkClock(m.clock)
                m.io.out.writeResponse.setSourceClock(m.clock)

                val len       = tc.len
                val addr      = tc.addr
                val b         = nextBoundary(addr)
                val firstLen  = b - addr
                val secondLen = len - firstLen

                thread("in.readAddress") {
                  m.io.in.readAddress.enqueue(Address(addr, len - 1))
                }

                thread("in.readData") {
                  for (i <- 0 until len) {
                    m.io.in.readData
                      .expectDequeue(ReadData(i.U, (i == (len - 1)).B))
                  }
                }

                thread("out.readAddress") {
                  var addrCounter = 0
                  var lenCounter  = len
                  while (lenCounter > 0) {
                    val a                  = addr + addrCounter
                    val availableAddresses = (nextBoundary(a) - a)
                    val availableBeats     = availableAddresses / bytesPerWord
                    val l                  = min(availableBeats, lenCounter)
                    m.io.out.readAddress
                      .expectDequeue(Address(a, l - 1))
                    addrCounter += availableAddresses
                    lenCounter -= l
                  }
                }

                thread("out.readData") {
                  for (i <- 0 until len) {
                    val last =
                      ((i * bytesPerWord + addr) % boundary == (boundary - bytesPerWord)) || (i == (len - 1))
                    m.io.out.readData.enqueue(ReadData(i.U, last.B))
                  }
                }
              }
            }
          }

          def splitUpWriteTest(tc: Test): Unit = {
            it("write " + tc.message) {
              decoupledTest(
                new MemBoundarySplitter(config, boundary, maxLen)
              ) { m =>
                m.io.in.readAddress.setSourceClock(m.clock)
                m.io.in.readData.setSinkClock(m.clock)
                m.io.in.writeAddress.setSourceClock(m.clock)
                m.io.in.writeData.setSourceClock(m.clock)
                m.io.in.writeResponse.setSinkClock(m.clock)

                m.io.out.readAddress.setSinkClock(m.clock)
                m.io.out.readData.setSourceClock(m.clock)
                m.io.out.writeAddress.setSinkClock(m.clock)
                m.io.out.writeData.setSinkClock(m.clock)
                m.io.out.writeResponse.setSourceClock(m.clock)

                val len       = tc.len
                val addr      = tc.addr
                val b         = nextBoundary(addr)
                val firstLen  = b - addr
                val secondLen = len - firstLen

                thread("in.writeAddress") {
                  m.io.in.writeAddress.enqueue(Address(addr, len - 1))
                }

                thread("in.writeData") {
                  for (i <- 0 until len) {
                    m.io.in.writeData
                      .enqueue(WriteData(i.U, (i == (len - 1)).B))
                  }
                }

                thread("out.writeAddress") {
                  var addrCounter = 0
                  var lenCounter  = len
                  while (lenCounter > 0) {
                    val a                  = addr + addrCounter
                    val availableAddresses = (nextBoundary(a) - a)
                    val availableBeats     = availableAddresses / bytesPerWord
                    val l                  = min(availableBeats, lenCounter)
                    m.io.out.writeAddress
                      .expectDequeue(Address(a, l - 1))
                    addrCounter += availableAddresses
                    lenCounter -= l
                  }
                }

                thread("out.writeData") {
                  for (i <- 0 until len) {
                    val last =
                      ((i * bytesPerWord + addr) % boundary == (boundary - bytesPerWord)) || (i == (len - 1))
                    m.io.out.writeData
                      .expectDequeue(WriteData(i.U, last.B))
                  }
                }

                thread("out.writeResponse") {
                  val numResponses = 1 + tensil.util.divCeil(
                    (len * bytesPerWord - (nextBoundary(addr) - addr)),
                    boundary
                  )
                  for (_ <- 0 until numResponses)
                    m.io.out.writeResponse.enqueue(WriteResponse())
                }

                thread("in.writeResponse") {
                  m.io.in.writeResponse.expectDequeue(WriteResponse())
                }
              }
            }
          }

          val cases = Array(
            Test(
              boundary - 128 * bytesPerWord,
              256,
              "request does cross a boundary"
            ),
            Test(
              boundary * 100 - bytesPerWord,
              256,
              "request has a high address near a boundary"
            ),
          )

          for (test <- cases) {
            splitUpReadTest(test)
            splitUpWriteTest(test)
          }
        }
      }
    }
  }
}
