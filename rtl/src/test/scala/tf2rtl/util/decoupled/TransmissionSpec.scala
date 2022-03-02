/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chisel3.util.Decoupled
import chiseltest._
import tensil.FunUnitSpec
import tensil.decoupled.{Driver => DecoupledDriver}

class TransmissionSpec extends FunUnitSpec {
  describe("DecoupledTransmission") {
    describe("when inWidth = 32b and outWidth = 80b") {
      it("should work") {
        test(new Transmission(32, 80)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          val t = fork {
            m.io.out.expectDequeue("h09080706050403020100".U)
          }

          m.io.in.enqueue("h03020100".U)
          m.io.in.enqueue("h07060504".U)
          m.io.in.enqueue("h00000908".U)

          t.join()
        }
      }
    }

    describe("when inWidth equals outWidth") {
      it("should pass data through") {
        test(new Transmission(32, 32)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          fork {
            m.io.out.expectDequeue(123.U)
          }

          m.io.in.enqueue(123.U)

          m.clock.step(10)
        }
      }
    }

    describe("when inWidth equals twice outWidth") {
      it("should perform a read and write") {
        test(new Transmission(64, 32)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          val data = (BigInt(456) << 32) | 123
          m.io.in.enqueue(data.U)
          m.io.out.expectDequeue(123.U)
          m.io.out.expectDequeue(456.U)
        }
      }

      it("should perform repeated writes") {
        test(new Transmission(64, 32)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          val data0 = (BigInt(456) << 32) | 123
          val data1 = (BigInt(789) << 32) | 654
          fork {
            m.io.out.expectDequeue(123.U)
            m.io.out.expectDequeue(456.U)
            m.io.out.expectDequeue(654.U)
            m.io.out.expectDequeue(789.U)
          }
          m.io.in.enqueue(data0.U)
          m.io.in.enqueue(data1.U)
        }
      }
    }

    describe("when outWidth equals twice the inWidth") {
      it("should perform a read and write") {
        test(new Transmission(32, 64)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          val data = (BigInt(456) << 32) | 123
          fork {
            m.io.out.expectDequeue(data.U)
          }
          m.io.in.enqueue(123.U)
          m.io.in.enqueue(456.U)
        }
      }

      it("should perform repeated reads") {
        test(new Transmission(32, 64)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          val data0 = (BigInt(456) << 32) | 123
          val data1 = (BigInt(789) << 32) | 654
          fork {
            m.io.out.expectDequeue(data0.U)
            m.io.out.expectDequeue(data1.U)
          }
          m.io.in.enqueue(123.U)
          m.io.in.enqueue(456.U)
          m.io.in.enqueue(654.U)
          m.io.in.enqueue(789.U)
        }
      }
    }

    describe("when inWidth equals thrice outWidth") {
      it("should perform a read and write") {
        test(new Transmission(96, 32)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          val data = (BigInt(789) << 64) | (BigInt(456) << 32) | 123
          m.io.in.enqueue(data.U)
          m.io.out.expectDequeue(123.U)
          m.io.out.expectDequeue(456.U)
          m.io.out.expectDequeue(789.U)
        }
      }

      it("should handle a large quantity of data") {
        test(new Transmission(96, 32)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          fork {
            for (i <- 0 until 100) {
              m.io.out.expectDequeue((3 * i + 2).U)
              m.io.out.expectDequeue((3 * i + 1).U)
              m.io.out.expectDequeue((3 * i).U)
            }
          }

          for (i <- 0 until 100) {
            val data =
              (BigInt(3 * i) << 64) | (BigInt(3 * i + 1) << 32) | (3 * i + 2)
            m.io.in.enqueue(data.U)
          }
        }
      }
    }

    describe("when outWidth equals thrice inWidth") {
      it("should perform a read and write") {
        test(new Transmission(32, 96)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          val data = (BigInt(789) << 64) | (BigInt(456) << 32) | 123
          m.io.in.enqueue(123.U)
          m.io.in.enqueue(456.U)
          m.io.in.enqueue(789.U)
          m.io.out.expectDequeue(data.U)
        }
      }

      it("should handle a large quantity of data") {
        test(new Transmission(32, 96)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          fork {
            for (i <- 0 until 100) {
              val data =
                (BigInt(3 * i) << 64) | (BigInt(3 * i + 1) << 32) | (3 * i + 2)
              m.io.out.expectDequeue(data.U)
            }
          }

          for (i <- 0 until 100) {
            m.io.in.enqueue((3 * i + 2).U)
            m.io.in.enqueue((3 * i + 1).U)
            m.io.in.enqueue((3 * i).U)
          }
        }
      }
    }

    // when array size = 4, datawidth = 16, and we write 5 addresses
    describe("when inWidth = 32 bits and outWidth = 64 bits") {
      it("should write 5 x 64 bits") {
        test(new Transmission(32, 64)) { m =>
          m.io.in.setSourceClock(m.clock)
          m.io.out.setSinkClock(m.clock)

          var covered = false

          fork {
            for (i <- 0 until 5) {
              val data =
                (BigInt(4 * i) << 48) | (BigInt(4 * i + 1) << 32) | (BigInt(
                  4 * i + 2
                ) << 16) | BigInt(4 * i + 3)
              m.io.out.expectDequeue(data.U)
            }
            covered = true
          }

          for (i <- 0 until 5) {
            m.io.in.enqueue((((4 * i + 2) << 16) | (4 * i + 3)).U)
            m.io.in.enqueue(((4 * i << 16) | (4 * i + 1)).U)
          }

          m.clock.step(10)

          assert(covered)

          m.clock.step(500)

          covered = false

          fork {
            for (i <- 5 until 10) {
              val data =
                (BigInt(4 * i) << 48) | (BigInt(4 * i + 1) << 32) | (BigInt(
                  4 * i + 2
                ) << 16) | BigInt(4 * i + 3)
              m.io.out.expectDequeue(data.U)
            }
            covered = true
          }

          for (i <- 5 until 10) {
            m.io.in.enqueue((((4 * i + 2) << 16) | (4 * i + 3)).U)
            m.io.in.enqueue(((4 * i << 16) | (4 * i + 1)).U)
          }

          m.clock.step(10)

          assert(covered)
        }
      }
    }

    describe("when connecting transmissions back to back") {
      class BackToBackTransmissions(outerWidth: Int, innerWidth: Int)
          extends Module {
        val io = IO(new Bundle {
          val in  = Flipped(Decoupled(UInt(outerWidth.W)))
          val out = Decoupled(UInt(outerWidth.W))
        })

        val t0 = Module(new Transmission(outerWidth, innerWidth))
        val t1 = Module(new Transmission(innerWidth, outerWidth))

        t0.io.in <> io.in
        t1.io.in <> t0.io.out
        io.out <> t1.io.out
        t0.io.error := false.B
        t1.io.error := false.B
      }
      describe("when outer width = 64 and inner width = 16") {
        it("should produce same data that was entered #0") {
          test(new BackToBackTransmissions(64, 16)) { m =>
            m.io.in.setSourceClock(m.clock)
            m.io.out.setSinkClock(m.clock)

            var covered = false

            val data = BigInt(0x01020304050607L)
            m.io.in.enqueue(data.U)
            m.io.out.expectDequeue(data.U)
          }
        }
      }
    }
  }
}
