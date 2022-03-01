package tensil.mem

import chisel3._
import chisel3.experimental.BundleLiterals._
import chiseltest._
import tensil.FunUnitSpec

class StrideHandlerSpec extends FunUnitSpec {
  describe("StrideHandler") {
    describe(
      "when depth = 256, strideDepth = 7, inGen = MemControl and outGen = MemRequest"
    ) {
      val depth       = 256
      val strideDepth = 7
      val inGen       = new MemControlWithStride(depth, strideDepth)
      val outGen      = new MemControl(depth)

      def testStride(stride: Int) = {
        val s = stride match {
          case 1   => 0
          case 2   => 1
          case 4   => 2
          case 8   => 3
          case 16  => 4
          case 32  => 5
          case 64  => 6
          case 128 => 7
        }
        it("should handle size") {
          test(new StrideHandler(inGen, outGen, depth, strideDepth)) { m =>
            m.io.in.setSourceClock(m.clock)
            m.io.out.setSinkClock(m.clock)

            val t0 = fork {
              m.io.in.enqueue(
                new MemControlWithStride(depth, strideDepth)
                  .Lit(
                    _.address -> 0.U,
                    _.write   -> false.B,
                    _.size    -> 7.U,
                    _.stride  -> s.U,
                    _.reverse -> false.B
                  )
              )
            }
            if (stride == 1) {
              m.io.out.expectDequeue(
                new MemControl(depth).Lit(
                  _.address -> 0.U,
                  _.write   -> false.B,
                  _.size    -> 7.U
                )
              )
            } else {
              for (i <- 0 until 8) {
                m.io.out.expectDequeue(
                  new MemControl(depth)
                    .Lit(
                      _.address -> (i * stride).U,
                      _.write   -> false.B,
                      _.size    -> 0.U
                    )
                )
              }
            }
            t0.join()

            val t1 = fork {
              m.io.in.enqueue(
                new MemControlWithStride(depth, strideDepth)
                  .Lit(
                    _.address -> 123.U,
                    _.write   -> false.B,
                    _.size    -> 18.U,
                    _.stride  -> s.U,
                    _.reverse -> false.B
                  )
              )
            }
            if (stride == 1) {
              m.io.out.expectDequeue(
                new MemControl(depth)
                  .Lit(_.address -> 123.U, _.write -> false.B, _.size -> 18.U)
              )
            } else {
              for (i <- 0 until 19) {
                m.io.out.expectDequeue(
                  new MemControl(depth)
                    .Lit(
                      _.address -> (123 + (i * stride)).U,
                      _.write   -> false.B,
                      _.size    -> 0.U
                    )
                )
              }
            }

            t1.join()
          }
        }
      }

      describe("when stride = 1") {
        testStride(1)
      }

      describe("when stride = 4") {
        testStride(4)
      }
    }
  }
}
