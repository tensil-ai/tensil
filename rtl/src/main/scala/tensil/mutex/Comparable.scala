package tensil.mutex

import chisel3._

trait Comparable[T <: Data] {
  self: T =>
  def ===(other: T): Bool
}
