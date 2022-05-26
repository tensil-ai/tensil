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

trait Comparable[T <: Data] {
  self: T =>
  def ===(other: T): Bool
}

class LockPool[T <: Data with Comparable[T]](
    gen: T,
    // depth: Int,
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

//   val a = Queue(io.a.in, 2)
//   val b = Queue(io.b.in, 2)

  val actor = VecInit(io.actor.map(a => Queue(a.in, 2)))

  val lockControl = Queue(io.lock, 2)

  val lock = RegInit(
    VecInit(
      Array.fill(numLocks)(
        zero(new ConditionalReleaseLock(gen, numActors, maxDelay))
        // ConditionalReleaseLock(
        //   gen,
        //   numActors,
        //   maxDelay,
        //   false.B,
        //   0.U,
        //   0.U,
        //   zero(gen),
        //   //   gen.Lit(_.address -> 0.U, _.write -> false.B)
        // )
      )
    )
  )
//   val actorValid = VecInit(Array(a, b).map(_.valid))
//   val actorBits  = VecInit(Array(a, b).map(_.bits))
//   val idA        = 0.U
//   val idB        = 1.U

  // we need a way to map an address to the block to which it belong
//   def block(address: UInt): UInt = {
//     val blockSize = depth / numLocks
//     address / blockSize.U
//   }

//   // the lock that A needs to check to process the request
//   val lockA  = lock(block(a.bits.address))
//   val blockA = lockA.held && lockA.by =/= idA
//   // the lock that B needs to check to process the request
//   val lockB  = lock(block(b.bits.address))
//   val blockB = lockB.held && lockB.by =/= idB

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
    when(l.held && actor(l.by).valid && actor(l.by).bits === l.cond) {
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

//   // use locks to decide when to allow requests
//   when(blockA) {
//     when(blockB) {
//       // deadlock: prefer A
//       io.a.out <> a
//       b.ready := false.B
//       io.b.out.valid := false.B
//       io.b.out.bits := DontCare
//       // signal out when deadlock happens so we know that something is wrong
//       io.deadlocked.bits := true.B
//       io.deadlocked.valid := true.B
//     }.otherwise {
//       // block A
//       a.ready := false.B
//       io.a.out.valid := false.B
//       io.a.out.bits := DontCare
//       io.b.out <> b
//     }
//   }.otherwise {
//     when(blockB) {
//       // block B
//       io.a.out <> a
//       b.ready := false.B
//       io.b.out.valid := false.B
//       io.b.out.bits := DontCare
//     }.otherwise {
//       // everything can proceed
//       io.a.out <> a
//       io.b.out <> b
//     }
//   }
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
