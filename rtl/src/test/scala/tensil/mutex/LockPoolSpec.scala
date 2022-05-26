/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mutex

import chisel3._
import chiseltest._
import tensil.FunUnitSpec
import chisel3.util.log2Ceil
import chisel3.experimental.BundleLiterals._

class LockPoolSpec extends FunUnitSpec {
  describe("LockPool") {
    describe(
      "when numActors = 2, numLocks = 2 and interface is memory requests"
    ) {
      val numActors = 2
      val numLocks  = 2
      val depth     = 8
      val gen       = new Request(depth)
      def select(r: Request): UInt = {
        val blockSize = depth / numLocks
        r.address / blockSize.U
      }

      def init(): LockPool[Request] =
        new LockPool(gen, numActors, numLocks, select)

      it("should allow requests to proceed when there are no locks acquired") {
        decoupledTest(init()) { m =>
          for (actor <- m.io.actor) {
            actor.in.setSourceClock(m.clock)
            actor.out.setSinkClock(m.clock)
          }

          val a = m.io.actor(0)
          val b = m.io.actor(1)

          thread("a.in") {
            a.in.enqueue(Request(depth, 0.U, false.B))
          }

          thread("b.in") {
            b.in.enqueue(Request(depth, 0.U, false.B))
          }

          thread("a.out") {
            a.out.expectDequeue(Request(depth, 0.U, false.B))
          }

          thread("b.out") {
            b.out.expectDequeue(Request(depth, 0.U, false.B))
          }
        }
      }

      it(
        "should allow A to acquire lock then hold requests on B until release condition is met on A"
      ) {
        decoupledTest(init()) { m =>
          for (actor <- m.io.actor) {
            actor.in.setSourceClock(m.clock)
            actor.out.setSinkClock(m.clock)
          }
          m.io.lock.setSourceClock(m.clock)

          val delay = 10
          val a     = m.io.actor(0)
          val b     = m.io.actor(1)

          thread("lock") {
            m.io.lock.enqueue(
              ConditionalReleaseLockControl(
                gen,
                numActors,
                numLocks,
                m.maxDelay,
                0.U,
                true.B,
                0.U,
                0.U,
                Request(depth, 1.U, true.B)
              )
            )
          }

          thread("a.in") {
            a.in.enqueue(Request(depth, 0.U, false.B))
            for (_ <- 0 until delay) {
              m.clock.step()
            }
            a.in.enqueue(Request(depth, 1.U, true.B))
          }

          thread("b.in") {
            m.clock.step()
            b.in.enqueue(Request(depth, 0.U, false.B))
          }

          thread("a.out") {
            a.out.expectDequeue(Request(depth, 0.U, false.B))
            a.out.expectDequeue(Request(depth, 1.U, true.B))
          }

          thread("b.out") {
            for (_ <- 0 until delay) {
              b.out.valid.expect(false.B)
              m.clock.step()
            }
            b.out.expectDequeue(Request(depth, 0.U, false.B))
          }
        }
      }

      it(
        "should allow B to acquire lock then hold requests on A until release condition is met on B"
      ) {
        decoupledTest(init()) { m =>
          for (actor <- m.io.actor) {
            actor.in.setSourceClock(m.clock)
            actor.out.setSinkClock(m.clock)
          }
          m.io.lock.setSourceClock(m.clock)

          val delay = 10
          val a     = m.io.actor(0)
          val b     = m.io.actor(1)

          thread("lock") {
            m.io.lock.enqueue(
              ConditionalReleaseLockControl(
                gen,
                numActors,
                numLocks,
                m.maxDelay,
                0.U,
                true.B,
                1.U,
                0.U,
                Request(depth, 1.U, true.B)
              )
            )
          }

          thread("b.in") {
            b.in.enqueue(Request(depth, 0.U, false.B))
            for (_ <- 0 until delay) {
              m.clock.step()
            }
            b.in.enqueue(Request(depth, 1.U, true.B))
          }

          thread("a.in") {
            m.clock.step()
            a.in.enqueue(Request(depth, 0.U, false.B))
          }

          thread("b.out") {
            b.out.expectDequeue(Request(depth, 0.U, false.B))
            b.out.expectDequeue(Request(depth, 1.U, true.B))
          }

          thread("a.out") {
            for (_ <- 0 until delay) {
              a.out.valid.expect(false.B)
              m.clock.step()
            }
            a.out.expectDequeue(Request(depth, 0.U, false.B))
          }
        }
      }

      it("should not allow B to acquire a lock when it is held by A") {
        decoupledTest(init()) { m =>
          for (actor <- m.io.actor) {
            actor.in.setSourceClock(m.clock)
            actor.out.setSinkClock(m.clock)
          }
          m.io.lock.setSourceClock(m.clock)
          m.io.locked.setSinkClock(m.clock)

          thread("lock") {
            m.io.lock.enqueue(
              ConditionalReleaseLockControl(
                gen,
                numActors,
                numLocks,
                m.maxDelay,
                0.U,
                true.B,
                0.U,
                0.U,
                Request(depth, 1.U, true.B)
              )
            )

            m.io.lock.enqueue(
              ConditionalReleaseLockControl(
                gen,
                numActors,
                numLocks,
                m.maxDelay,
                0.U,
                true.B,
                1.U,
                0.U,
                Request(depth, 1.U, true.B)
              )
            )
          }

          thread("locked") {
            m.io.locked.expectDequeue(
              ConditionalReleaseLockControl(
                gen,
                numActors,
                numLocks,
                m.maxDelay,
                0.U,
                true.B,
                0.U,
                0.U,
                Request(depth, 1.U, true.B)
              )
            )

            for (_ <- 0 until 10)
              m.io.locked.valid.expect(false.B)
          }
        }
      }

      // deadlock: A holds 0, B holds 1, A is waiting for 0, B is waiting for 1. In this case we should allow A to proceed.
      it(
        "should allow A to proceed when deadlocked, and also signal deadlock"
      ) {

        decoupledTest(init()) { m =>
          for (actor <- m.io.actor) {
            actor.in.setSourceClock(m.clock)
            actor.out.setSinkClock(m.clock)
          }
          m.io.lock.setSourceClock(m.clock)
          m.io.locked.setSinkClock(m.clock)
          m.io.deadlocked.setSinkClock(m.clock)

          val a = m.io.actor(0)
          val b = m.io.actor(1)

          thread("lock") {
            m.io.lock.enqueue(
              ConditionalReleaseLockControl(
                gen,
                numActors,
                numLocks,
                m.maxDelay,
                0.U,
                true.B,
                0.U,
                0.U,
                Request(depth, 1.U, true.B)
              )
            )

            m.io.lock.enqueue(
              ConditionalReleaseLockControl(
                gen,
                numActors,
                numLocks,
                m.maxDelay,
                1.U,
                true.B,
                1.U,
                0.U,
                Request(depth, 1.U, true.B)
              )
            )
          }

          thread("a.in") {
            m.clock.step(2)
            a.in.enqueue(Request(depth, (depth / 2).U, false.B))
            a.in.enqueue(Request(depth, 1.U, true.B))
          }

          thread("b.in") {
            m.clock.step(2)
            b.in.enqueue(Request(depth, 0.U, false.B))
          }

          thread("a.out") {
            a.out.expectDequeue(Request(depth, (depth / 2).U, false.B))
            a.out.expectDequeue(Request(depth, 1.U, true.B))
          }

          thread("b.out") {
            b.out.expectDequeue(Request(depth, 0.U, false.B))
          }

          thread("deadlocked") {
            m.io.deadlocked.expectDequeue(true.B)
          }
        }
      }

    }
  }
}

class Request(val depth: Long) extends Bundle with Comparable[Request] {
  val address = UInt(log2Ceil(depth).W)
  val write   = Bool()

  def ===(other: Request): Bool = {
    address === other.address && write === other.write
  }
}

object Request {
  def apply(
      depth: Long,
      address: UInt,
      write: Bool,
  ): Request = {
    (new Request(depth)).Lit(
      _.address -> address,
      _.write   -> write,
    )
  }
}
