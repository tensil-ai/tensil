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
    val locked = Decoupled(
      new ConditionalReleaseLockControl(
        lockCond,
        numPorts,
        numBlocks,
        maxDelay
      )
    )
    val deadlocked = Decoupled(Bool())
  })

  val a           = Queue(io.a.in, 2)
  val b           = Queue(io.b.in, 2)
  val lockControl = Queue(io.lock, 2)

  val lock = RegInit(
    VecInit(
      Array.fill(numBlocks)(
        ConditionalReleaseLock(
          lockCond,
          numPorts,
          maxDelay,
          false.B,
          0.U,
          0.U,
          lockCond.Lit(_.address -> 0.U, _.write -> false.B)
        )
      )
    )
  )
  val actorValid = VecInit(Array(a, b).map(_.valid))
  val actorBits  = VecInit(Array(a, b).map(_.bits))
  val idA        = 0.U
  val idB        = 1.U

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

  // locked output for testing
  io.locked.bits <> lockControl.bits
  io.locked.valid := lockControl.fire

  // signal when deadlocked
  io.deadlocked.bits := DontCare
  io.deadlocked.valid := false.B

  // acquire lock when lock control comes in
  val l = lock(lockControl.bits.lock)
  when(l.held) {
    when(l.by === lockControl.bits.by) {
      // lock continues to be held by same port unless request specifies acquire = false (i.e. manual release)
      lockControl.ready := true.B
      when(lockControl.valid) {
        l.held := lockControl.bits.acquire
        // update release condition
        l.cond <> lockControl.bits.cond
      }
    }.otherwise {
      // other port holds lock, have to wait for it to release to acquire it
      lockControl.ready := false.B
    }
  }.otherwise {
    // can acquire it
    lockControl.ready := true.B
    when(lockControl.valid) {
      l.held := lockControl.bits.acquire
      l.by := lockControl.bits.by
      l.cond <> lockControl.bits.cond
    }
  }

  // release lock when condition is observed
  for (l <- lock) {
    when(l.held && actorValid(l.by) && actorBits(l.by) === l.cond) {
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
      // signal out when deadlock happens so we know that something is wrong
      io.deadlocked.bits := true.B
      io.deadlocked.valid := true.B
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
      // everything can proceed
      io.a.out <> a
      io.b.out <> b
    }
  }
}

class ScratchSpec extends FunUnitSpec {
  describe("Scratch") {
    describe("when depth = 8") {
      val depth = 8

      it("should allow requests to proceed when there are no locks acquired") {
        decoupledTest(new Scratch(depth)) { m =>
          m.io.a.in.setSourceClock(m.clock)
          m.io.a.out.setSinkClock(m.clock)
          m.io.b.in.setSourceClock(m.clock)
          m.io.b.out.setSinkClock(m.clock)

          thread("a.in") {
            m.io.a.in.enqueue(Request(depth, 0.U, false.B))
          }

          thread("b.in") {
            m.io.b.in.enqueue(Request(depth, 0.U, false.B))
          }

          thread("a.out") {
            m.io.a.out.expectDequeue(Request(depth, 0.U, false.B))
          }

          thread("b.out") {
            m.io.b.out.expectDequeue(Request(depth, 0.U, false.B))
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
          m.io.lock.setSourceClock(m.clock)

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
                Request(depth, 1.U, true.B)
              )
            )
          }

          thread("a.in") {
            m.io.a.in.enqueue(Request(depth, 0.U, false.B))
            for (_ <- 0 until delay) {
              m.clock.step()
            }
            m.io.a.in.enqueue(Request(depth, 1.U, true.B))
          }

          thread("b.in") {
            m.clock.step()
            m.io.b.in.enqueue(Request(depth, 0.U, false.B))
          }

          thread("a.out") {
            m.io.a.out.expectDequeue(Request(depth, 0.U, false.B))
            m.io.a.out.expectDequeue(Request(depth, 1.U, true.B))
          }

          thread("b.out") {
            for (_ <- 0 until delay) {
              m.io.b.out.valid.expect(false.B)
              m.clock.step()
            }
            m.io.b.out.expectDequeue(Request(depth, 0.U, false.B))
          }
        }
      }

      it(
        "should allow B to acquire lock then hold requests on A until release condition is met on B"
      ) {
        decoupledTest(new Scratch(depth)) { m =>
          m.io.a.in.setSourceClock(m.clock)
          m.io.a.out.setSinkClock(m.clock)
          m.io.b.in.setSourceClock(m.clock)
          m.io.b.out.setSinkClock(m.clock)
          m.io.lock.setSourceClock(m.clock)

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
                m.idB,
                0.U,
                Request(depth, 1.U, true.B)
              )
            )
          }

          thread("b.in") {
            m.io.b.in.enqueue(Request(depth, 0.U, false.B))
            for (_ <- 0 until delay) {
              m.clock.step()
            }
            m.io.b.in.enqueue(Request(depth, 1.U, true.B))
          }

          thread("a.in") {
            m.clock.step()
            m.io.a.in.enqueue(Request(depth, 0.U, false.B))
          }

          thread("b.out") {
            m.io.b.out.expectDequeue(Request(depth, 0.U, false.B))
            m.io.b.out.expectDequeue(Request(depth, 1.U, true.B))
          }

          thread("a.out") {
            for (_ <- 0 until delay) {
              m.io.a.out.valid.expect(false.B)
              m.clock.step()
            }
            m.io.a.out.expectDequeue(Request(depth, 0.U, false.B))
          }
        }
      }

      it("should not allow B to acquire a lock when it is held by A") {
        decoupledTest(new Scratch(depth)) { m =>
          m.io.a.in.setSourceClock(m.clock)
          m.io.a.out.setSinkClock(m.clock)
          m.io.b.in.setSourceClock(m.clock)
          m.io.b.out.setSinkClock(m.clock)
          m.io.lock.setSourceClock(m.clock)
          m.io.locked.setSinkClock(m.clock)

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
                Request(depth, 1.U, true.B)
              )
            )

            m.io.lock.enqueue(
              ConditionalReleaseLockControl(
                m.lockCond,
                m.numPorts,
                m.numBlocks,
                m.maxDelay,
                0.U,
                true.B,
                m.idB,
                0.U,
                Request(depth, 1.U, true.B)
              )
            )
          }

          thread("locked") {
            m.io.locked.expectDequeue(
              ConditionalReleaseLockControl(
                m.lockCond,
                m.numPorts,
                m.numBlocks,
                m.maxDelay,
                0.U,
                true.B,
                m.idA,
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

        decoupledTest(new Scratch(depth)) { m =>
          m.io.a.in.setSourceClock(m.clock)
          m.io.a.out.setSinkClock(m.clock)
          m.io.b.in.setSourceClock(m.clock)
          m.io.b.out.setSinkClock(m.clock)
          m.io.lock.setSourceClock(m.clock)
          m.io.locked.setSinkClock(m.clock)
          m.io.deadlocked.setSinkClock(m.clock)

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
                Request(depth, 1.U, true.B)
              )
            )

            m.io.lock.enqueue(
              ConditionalReleaseLockControl(
                m.lockCond,
                m.numPorts,
                m.numBlocks,
                m.maxDelay,
                1.U,
                true.B,
                m.idB,
                0.U,
                Request(depth, 1.U, true.B)
              )
            )
          }

          thread("a.in") {
            m.clock.step(2)
            m.io.a.in.enqueue(Request(depth, (depth / 2).U, false.B))
            m.io.a.in.enqueue(Request(depth, 1.U, true.B))
          }

          thread("b.in") {
            m.clock.step(2)
            m.io.b.in.enqueue(Request(depth, 0.U, false.B))
          }

          thread("a.out") {
            m.io.a.out.expectDequeue(Request(depth, (depth / 2).U, false.B))
            m.io.a.out.expectDequeue(Request(depth, 1.U, true.B))
          }

          thread("b.out") {
            m.io.b.out.expectDequeue(Request(depth, 0.U, false.B))
          }

          thread("deadlocked") {
            m.io.deadlocked.expectDequeue(true.B)
          }
        }
      }

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
      address: UInt,
      write: Bool,
  ): Request = {
    (new Request(depth)).Lit(
      _.address -> address,
      _.write   -> write,
    )
  }
}

class Lock(val numActors: Int) extends Bundle {
  val held = Bool()
  val by   = UInt(log2Ceil(numActors).W)
}

object Lock {
  def apply(numActors: Int, held: Bool, by: UInt): Lock = {
    (new Lock(numActors)).Lit(
      _.held -> held,
      _.by   -> by,
    )
  }

  def apply(numActors: Int): Lock = apply(numActors, false.B, 0.U)
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
}

object ConditionalReleaseLock {
  def apply[T <: Data](
      gen: T,
      numActors: Int,
      maxDelay: Int,
      held: Bool,
      by: UInt,
      delayRelease: UInt,
      cond: T,
  ): ConditionalReleaseLock[T] = {
    (new ConditionalReleaseLock(gen, numActors, maxDelay)).Lit(
      _.held         -> held,
      _.by           -> by,
      _.delayRelease -> delayRelease,
      _.cond         -> cond,
    )
  }

  def apply[T <: Data](
      gen: T,
      numActors: Int,
      maxDelay: Int
  ): ConditionalReleaseLock[T] =
    apply(gen, numActors, maxDelay, false.B, 0.U, 0.U, zero(gen))
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
