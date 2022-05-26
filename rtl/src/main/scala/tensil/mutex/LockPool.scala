/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mutex

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.experimental.BundleLiterals._
import chisel3.util.Decoupled
import tensil.util.decoupled.Counter
import chisel3.util.Queue
import chisel3.util.DecoupledIO
import tensil.util.zero
import chisel3.util.log2Ceil

class LockPool[T <: Data with Comparable[T]](
    gen: T,
    numActors: Int,
    numLocks: Int,
    select: T => UInt
) extends Module {
  val maxDelay = 1 << 4
  val io = IO(new Bundle {
    val actor = Vec(
      numActors,
      new Bundle {
        val in  = Flipped(Decoupled(gen))
        val out = Decoupled(gen)
      }
    )
    val lock = Flipped(
      Decoupled(
        new ConditionalReleaseLockControl(
          gen,
          numActors,
          numLocks,
          maxDelay
        )
      )
    )
    val locked = Decoupled(
      new ConditionalReleaseLockControl(
        gen,
        numActors,
        numLocks,
        maxDelay
      )
    )
    val deadlocked = Decoupled(Bool())
  })

  val actor       = VecInit(io.actor.map(a => Queue(a.in, 2)))
  val lockControl = Queue(io.lock, 2)

  val lock = RegInit(
    VecInit(
      Array.fill(numLocks)(
        zero(new ConditionalReleaseLock(gen, numActors, maxDelay))
      )
    )
  )

  val block = VecInit(for ((a, id) <- actor.zipWithIndex) yield {
    // block by default
    io.actor(id).out.noenq()
    a.ready := false.B
    val requiredLock = lock(select(a.bits))
    val blocked      = requiredLock.held && requiredLock.by =/= id.U
    when(!blocked) {
      // allow actor to proceed when not blocked
      io.actor(id).out <> a
    }
    blocked
  })

  // locked output facilitates testing - not required to be connected in production
  io.locked.bits <> lockControl.bits
  io.locked.valid := lockControl.fire

  // for signalling when deadlocked
  io.deadlocked.bits := DontCare
  io.deadlocked.valid := false.B

  def acquire(l: ConditionalReleaseLock[T]): Unit = {
    when(lockControl.valid) {
      l.held := lockControl.bits.acquire
      l.by := lockControl.bits.by
      l.cond <> lockControl.bits.cond
    }
  }
  def release(l: ConditionalReleaseLock[T]): Unit = {
    l.held := false.B
  }
  // this covers the case where the condition happens to be observed on the same
  // cycle the lock would have been acquired
  val incomingObserved =
    lockControl.valid && actor(lockControl.bits.by).fire && actor(
      lockControl.bits.by
    ).bits === lockControl.bits.cond
  // ready for new lock requests by default
  val requestedLock = lock(lockControl.bits.lock)
  // when requested lock held by a different actor than the one requesting, block until lock is released
  lockControl.ready := !(requestedLock.held && lockControl.bits.by =/= requestedLock.by)
  for ((l, id) <- lock.zipWithIndex) {
    val incoming = lockControl.bits.lock === id.U
    val observed = actor(l.by).fire && actor(l.by).bits === l.cond
    when(l.held) {
      when(observed) {
        when(incoming) {
          when(incomingObserved) {
            release(l)
          }.otherwise {
            // release then immediately try to acquire
            release(l)
            acquire(l)
          }
        }.otherwise {
          release(l)
        }
      }.otherwise {
        when(incoming) {
          when(l.by === lockControl.bits.by) {
            // same actor acquiring lock, so allowed
            acquire(l)
          }
        }
      }
    }.otherwise {
      when(incoming) {
        when(!incomingObserved) {
          acquire(l)
        }
      }
    }
  }

  // when all actors are blocked, allow the actor holding lock 0 to proceed
  when(block.reduce((a, b) => a && b)) {
    io.actor(lock(0.U).by).out <> actor(lock(0.U).by)
    // signal out when deadlock happens so we know that something is wrong
    io.deadlocked.bits := true.B
    io.deadlocked.valid := true.B
  }
}
