package tensil.mutex

import chisel3._

trait ConditionalRelease[T <: Data] {
  val delayRelease: UInt
  val cond: T
}
