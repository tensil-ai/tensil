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
    io.actor(id.U).out.noenq()
    a.ready := false.B
    val requiredLock = lock(select(a.bits))
    val blocked      = requiredLock.held && requiredLock.by =/= id.U
    when(!blocked) {
      // allow actor to proceed when not blocked
      io.actor(id.U).out <> a
    }
    blocked
  })

  // locked output facilitates testing - not required to be connected in production
  io.locked.bits <> lockControl.bits
  io.locked.valid := lockControl.fire

  // for signalling when deadlocked
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
    when(l.held && actor(l.by).fire && actor(l.by).bits === l.cond) {
      // release
      // TODO use release delay
      l.held := false.B
    }
  }

  // when all actors are blocked, allow the lowest index one to proceed.
  when(block.reduce((a, b) => a && b)) {
    io.actor(0.U).out <> actor(0.U)
    // signal out when deadlock happens so we know that something is wrong
    io.deadlocked.bits := true.B
    io.deadlocked.valid := true.B
  }
}
