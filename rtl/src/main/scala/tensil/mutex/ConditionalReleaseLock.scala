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
