package tensil.mem

import chisel3._
import chiseltest._
import tensil.FunUnitSpec

class RequestSplitterSpec extends FunUnitSpec {
  describe("RequestSplitter") {
    describe("when depth = 1024 and maxSize = 32") {
      val depth   = 1024
      val maxSize = 32

      it(
        "should split requests with size 32 or larger into several requests " +
          "with size smaller than 32"
      ) {
        decoupledTest(new RequestSplitter(depth, maxSize)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          def request(address: Int, size: Int): MemControl =
            MemControl(depth, address.U, size.U, false.B)

          thread("in") {
            m.io.in.enqueue(request(0, 32))
          }
          thread("out") {
            m.io.out.expectDequeue(request(0, 31))
            m.io.out.expectDequeue(request(32, 0))
          }

          thread("in") {
            m.io.in.enqueue(request(0, 1023))
          }
          thread("out") {
            for (i <- 0 until 32) {
              m.io.out.expectDequeue(request(i * maxSize, 31))
            }
          }
        }
      }
    }

    describe("when depth = 1024 and maxSize = 256") {
      val depth   = 1024
      val maxSize = 256

      it(
        "should split requests with size 256 or larger into several requests " +
          "with size smaller than 256"
      ) {
        decoupledTest(new RequestSplitter(depth, maxSize)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          def request(address: Int, size: Int): MemControl =
            MemControl(depth, address.U, size.U, false.B)

          thread("in") {
            m.io.in.enqueue(request(0, 256))
          }
          thread("out") {
            m.io.out.expectDequeue(request(0, 255))
            m.io.out.expectDequeue(request(256, 0))
          }

          thread("in") {
            m.io.in.enqueue(request(0, 1023))
          }
          thread("out") {
            m.io.out.expectDequeue(request(0, 255))
            m.io.out.expectDequeue(request(256, 255))
            m.io.out.expectDequeue(request(512, 255))
            m.io.out.expectDequeue(request(768, 255))
          }
        }
      }
    }
  }
}
