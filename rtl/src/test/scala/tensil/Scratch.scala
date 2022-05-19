/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.experimental.BundleLiterals._
import chisel3.util.Decoupled
import chiseltest._
import tensil.util.decoupled.Counter
import chisel3.util.Queue
import chisel3.util.DecoupledIO
import tensil.util.zero

class Scratch(depth: Int) extends Module {
  val numPorts  = 2
  val numBlocks = 2
  val maxDelay  = 1 << 4
  val lockCond  = new Request(depth)
  val io = IO(new Bundle {
    val a = new Bundle {
      val in  = Flipped(Decoupled(new Request(depth)))
      val out = Decoupled(new Request(depth))
    }
    val b = new Bundle {
      val in  = Flipped(Decoupled(new Request(depth)))
      val out = Decoupled(new Request(depth))
    }
    val lock = Flipped(
      Decoupled(
        new ConditionalReleaseLockControl(
          lockCond,
          numPorts,
          numBlocks,
          maxDelay
        )
      )
    )
  })

  val a           = Queue(io.a.in, 2)
  val b           = Queue(io.b.in, 2)
  val lockControl = Queue(io.lock, 2)

  val lock = RegInit(
    VecInit(
      Array.fill(numBlocks)(
        ConditionalReleaseLock(lockCond, numPorts, maxDelay)
      )
    )
  )
  val actor = VecInit(Array(a, b))
  val idA   = 0.U
  val idB   = 1.U

  // when do we lock and unlock
  // how do we break ties
  // when are requests allowed to proceed / blocked?

  // we need a way to map an address to the block to which it belong
  def block(address: UInt): UInt = {
    val blockSize = depth / numBlocks
    address / blockSize.U
  }

  // the lock that A needs to check to process the request
  val lockA  = lock(block(a.bits.address))
  val blockA = lockA.held && lockA.by =/= idA
  // the lock that B needs to check to process the request
  val lockB  = lock(block(b.bits.address))
  val blockB = lockB.held && lockB.by =/= idB

  // acquire lock when lock control comes in
  val l = lock(lockControl.bits.lock)
  when(l.held) {
    when(l.by === lockControl.bits.by) {
      // lock continues to be held by same port
      // have to update condition
      lockControl.ready := true.B
      l.cond <> lockControl.bits.cond
      // alternatively we could just treat this as have to wait to acquire lock again
    }.otherwise {
      // other port holds lock, have to wait for it to release to acquire it
      lockControl.ready := false.B
    }
  }.otherwise {
    // can acquire it
    // TODO check acquire flag
    when(lockControl.valid) {
      l.held := true.B
      l.by := lockControl.bits.by
      l.cond <> lockControl.bits.cond
    }
    lockControl.ready := true.B
  }

  // release lock when condition is observed
  for (l <- lock) {
    val ac = actor(l.by)
    when(ac.valid && ac.bits === l.cond) {
      // release
      // TODO use release delay
      l.held := false.B
    }
  }

  // use locks to decide when to allow requests
  when(blockA) {
    when(blockB) {
      // deadlock: prefer A
      io.a.out <> a
      b.ready := false.B
      io.b.out.valid := false.B
      io.b.out.bits := DontCare
      // TODO signal out when deadlock happens so we know that something is wrong
    }.otherwise {
      // block A
      a.ready := false.B
      io.a.out.valid := false.B
      io.a.out.bits := DontCare
      io.b.out <> b
    }
  }.otherwise {
    when(blockB) {
      // block B
      io.a.out <> a
      b.ready := false.B
      io.b.out.valid := false.B
      io.b.out.bits := DontCare
    }.otherwise {
      io.a.out <> a
      io.b.out <> b
    }
  }
}

class ScratchSpec extends FunUnitSpec {
  describe("Scratch") {
    describe("when depth = 1 << 8") {
      val depth = 8
      it("should allow requests to proceed when there are no locks") {
        decoupledTest(new Scratch(depth)) { m =>
          m.io.a.in.setSourceClock(m.clock)
          m.io.a.out.setSinkClock(m.clock)
          m.io.b.in.setSourceClock(m.clock)
          m.io.b.out.setSinkClock(m.clock)

          thread("a.in") {
            m.io.a.in.enqueue(Request(depth, 0, false))
          }

          thread("b.in") {
            m.io.b.in.enqueue(Request(depth, 0, false))
          }

          thread("a.out") {
            m.io.a.out.expectDequeue(Request(depth, 0, false))
          }

          thread("b.out") {
            m.io.b.out.expectDequeue(Request(depth, 0, false))
          }
        }
      }

      it(
        "should allow A to acquire lock then hold requests on B until release condition is met on A"
      ) {
        decoupledTest(new Scratch(depth)) { m =>
          m.io.a.in.setSourceClock(m.clock)
          m.io.a.out.setSinkClock(m.clock)
          m.io.b.in.setSourceClock(m.clock)
          m.io.b.out.setSinkClock(m.clock)

          val delay = 10

          thread("lock") {
            m.io.lock.enqueue(
              ConditionalReleaseLockControl(
                m.lockCond,
                m.numPorts,
                m.numBlocks,
                m.maxDelay,
                0.U,
                true.B,
                m.idA,
                0.U,
                Request(depth, 1, true)
              )
            )
          }

          thread("a.in") {
            m.io.a.in.enqueue(Request(depth, 0, false))
            for (_ <- 0 until delay) {
              m.clock.step()
            }
            m.io.a.in.enqueue(Request(depth, 1, true))
          }

          thread("b.in") {
            m.io.b.in.enqueue(Request(depth, 0, false))
          }

          thread("a.out") {
            m.io.a.out.expectDequeue(Request(depth, 0, false))
            m.io.a.out.expectDequeue(Request(depth, 1, true))
          }

          thread("b.out") {
            for (_ <- 0 until delay) {
              m.io.b.out.valid.expect(false.B)
              m.clock.step()
            }
            m.io.b.out.expectDequeue(Request(depth, 0, false))
          }
        }
      }

      //   it("should hold requests on A until release arrives on B") {
      //     decoupledTest(new Scratch(depth)) { m =>
      //       m.io.a.in.setSourceClock(m.clock)
      //       m.io.a.out.setSinkClock(m.clock)
      //       m.io.b.in.setSourceClock(m.clock)
      //       m.io.b.out.setSinkClock(m.clock)

      //       val delay = 10

      //       thread("a.in") {
      //         m.io.a.in.enqueue(Request(depth, 0, false, Signal.None))
      //       }

      //       thread("b.in") {
      //         m.io.b.in.enqueue(Request(depth, 0, false, Signal.Hold))
      //         for (_ <- 0 until delay) {
      //           m.clock.step()
      //         }
      //         m.io.b.in.enqueue(Request(depth, 0, false, Signal.Release))
      //       }

      //       thread("a.out") {
      //         for (_ <- 0 until delay) {
      //           m.io.a.out.valid.expect(false.B)
      //           m.clock.step()
      //         }
      //         m.io.a.out.expectDequeue(Request(depth, 0, false, Signal.None))
      //       }

      //       thread("b.out") {
      //         m.io.b.out.expectDequeue(Request(depth, 0, false, Signal.Hold))
      //         m.io.b.out.expectDequeue(Request(depth, 0, false, Signal.Release))
      //       }
      //     }
      //   }

      //   it(
      //     "should prefer to block B when holds are requested on both ports"
      //   ) {
      //     decoupledTest(new Scratch(depth)) { m =>
      //       m.io.a.in.setSourceClock(m.clock)
      //       m.io.a.out.setSinkClock(m.clock)
      //       m.io.b.in.setSourceClock(m.clock)
      //       m.io.b.out.setSinkClock(m.clock)

      //       thread("a.in") {
      //         m.io.a.in.enqueue(Request(depth, 0, false, Signal.Hold))
      //         m.io.a.in.enqueue(Request(depth, 0, false, Signal.None))
      //       }

      //       thread("b.in") {
      //         m.io.b.in.enqueue(Request(depth, 0, false, Signal.Hold))
      //       }

      //       thread("a.out") {
      //         m.io.a.out.expectDequeue(Request(depth, 0, false, Signal.Hold))
      //         m.io.a.out.expectDequeue(Request(depth, 0, false, Signal.None))
      //       }

      //       thread("b.out") {
      //         for (_ <- 0 until 5) {
      //           m.io.b.out.valid.expect(false.B)
      //         }
      //       }
      //     }
      //   }
    }
  }
}

class Request(val depth: Long) extends Bundle {
  val address = UInt(log2Ceil(depth).W)
  val write   = Bool()

  def ===(other: Request): Bool = {
    address === other.address && write === other.write
  }
}

object Request {
  def apply(
      depth: Long,
      address: Int,
      write: Boolean,
  ): Request = {
    (new Request(depth)).Lit(
      _.address -> address.U,
      _.write   -> write.B,
    )
  }
}

class Lock(val numActors: Int) extends Bundle {
  val held = Bool()
  val by   = UInt(log2Ceil(numActors).W)
}

object Lock {
  def apply(numActors: Int, held: Boolean, by: Int): Lock = {
    (new Lock(numActors)).Lit(
      _.held -> held.B,
      _.by   -> by.U,
    )
  }

  def apply(numActors: Int): Lock = apply(numActors, false, 0)
}

trait ConditionalRelease[T <: Data] {
  val delayRelease: UInt
  val cond: T
}

class ConditionalReleaseLock[T <: Data](
    gen: T,
    numActors: Int,
    val maxDelay: Int
) extends Lock(numActors)
    with ConditionalRelease[T] {
  val delayRelease = UInt(log2Ceil(maxDelay).W)
  val cond         = gen

  // override def cloneType: ConditionalReleaseLock[T] =
  //   new ConditionalReleaseLock[T](gen, numActors, maxDelay)
  // // .asInstanceOf[ConditionalReleaseLock[T]]
}

object ConditionalReleaseLock {
  def apply[T <: Data](
      gen: T,
      numActors: Int,
      maxDelay: Int,
      held: Boolean,
      by: Int,
      delayRelease: Int,
      cond: T,
  ): ConditionalReleaseLock[T] = {
    (new ConditionalReleaseLock(gen, numActors, maxDelay)).Lit(
      _.held         -> held.B,
      _.by           -> by.U,
      _.delayRelease -> delayRelease.U,
      _.cond         -> cond,
    )
  }

  def apply[T <: Data](
      gen: T,
      numActors: Int,
      maxDelay: Int
  ): ConditionalReleaseLock[T] =
    apply(gen, numActors, maxDelay: Int, false, 0, 0, zero(gen))
}

class LockControl(
    val numActors: Int,
    val numLocks: Int,
) extends Bundle {
  val lock    = UInt(log2Ceil(numLocks).W)
  val acquire = Bool() // false = release, true = acquire
  val by      = UInt(log2Ceil(numActors).W)
}

class ConditionalReleaseLockControl[T <: Data](
    gen: T,
    numActors: Int,
    numLocks: Int,
    val maxDelay: Int,
) extends LockControl(numActors, numLocks)
    with ConditionalRelease[T] {
  val delayRelease = UInt(log2Ceil(maxDelay).W)
  val cond         = gen

  // override def cloneType: ConditionalReleaseLockControl[T] =
  //   new ConditionalReleaseLockControl(gen, numActors, numLocks, maxDelay)
  //     .asInstanceOf[this.type]
}

object ConditionalReleaseLockControl {
  def apply[T <: Data](
      gen: T,
      numActors: Int,
      numLocks: Int,
      maxDelay: Int,
      lock: UInt,
      acquire: Bool,
      by: UInt,
      delayRelease: UInt,
      cond: T
  ): ConditionalReleaseLockControl[T] = {
    (new ConditionalReleaseLockControl(gen, numActors, numLocks, maxDelay)).Lit(
      _.lock         -> lock,
      _.acquire      -> acquire,
      _.by           -> by,
      _.delayRelease -> delayRelease,
      _.cond         -> cond
    )
  }
}
