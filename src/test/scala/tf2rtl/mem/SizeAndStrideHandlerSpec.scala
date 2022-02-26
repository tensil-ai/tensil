package tf2rtl.mem

import chisel3._
import chisel3.experimental.BundleLiterals._
import chiseltest._
import tf2rtl.FunUnitSpec

class SizeAndStrideHandlerSpec extends FunUnitSpec {
  describe("SizeHandler") {
    describe(
      "when depth = 256, strideDepth = 7, inGen = MemControl and outGen = MemRequest"
    ) {
      val depth       = 256
      val strideDepth = 7
      val inGen       = new MemControlWithStride(depth, strideDepth)
      val outGen      = new MemRequest(depth)

      def testStride(stride: Int, reverse: Boolean) = {
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
        it(s"should handle size with reverse = $reverse") {
          test(new SizeAndStrideHandler(inGen, outGen, depth, strideDepth)) {
            m =>
              m.io.in.setSourceClock(m.clock)
              m.io.out.setSinkClock(m.clock)

              val addr0 = if (reverse) 7 * stride else 0
              val t0 = fork {
                m.io.in.enqueue(
                  new MemControlWithStride(depth, strideDepth)
                    .Lit(
                      _.address -> addr0.U,
                      _.write   -> false.B,
                      _.size    -> 7.U,
                      _.stride  -> s.U,
                      _.reverse -> reverse.B,
                    )
                )
              }
              val idx0 = if (reverse) 7 to 0 by -1 else 0 to 7
              for (i <- idx0) {
                m.io.out.expectDequeue(
                  new MemRequest(depth)
                    .Lit(_.address -> (i * stride).U, _.write -> false.B)
                )
              }
              t0.join()

              val addr1 = if (reverse) 123 + (18 * stride) else 123
              val t1 = fork {
                m.io.in.enqueue(
                  new MemControlWithStride(depth, strideDepth)
                    .Lit(
                      _.address -> addr1.U,
                      _.write   -> false.B,
                      _.size    -> 18.U,
                      _.stride  -> s.U,
                      _.reverse -> reverse.B,
                    )
                )
              }
              val idx1 = if (reverse) 18 to 0 by -1 else 0 to 18
              for (i <- idx1) {
                m.io.out.expectDequeue(
                  new MemRequest(depth)
                    .Lit(
                      _.address -> (123 + (i * stride)).U,
                      _.write   -> false.B
                    )
                )
              }

              t1.join()
          }
        }
      }

      describe("when stride = 1") {
        testStride(1, false)
        testStride(1, true)
      }

      describe("when stride = 4") {
        testStride(4, false)
        testStride(4, true)
      }
    }
  }
}
