
package tf2rtl


import chisel3._
import chisel3.MultiIOModule
//import chisel3.{Aggregate, Bits, Module, Bool}
import chisel3.iotesters.PeekPokeTester

/**
  * This class is a kludge to fix the `poke` and `expect` methods of `PeekPokeTester`,
  * which reverse their input value before using the bits of the signal. This
  * behaviour is a longstanding bug in chisel3 [0]. This class should be used
  * everywhere that `PeekPokeTester` would otherwise be used.
  *
  * [0] https://github.com/freechipsproject/chisel-testers/issues/77
  * @param dut
  */
class FixedPeekPokeTester[+T <: MultiIOModule](dut: T) extends PeekPokeTester(dut) {
  /**
    * poke fixes the superclass' method by reversing the value before passing
    * it on
    *
    * @param signal
    * @param value
    */
  override def poke(signal: Aggregate, value: IndexedSeq[BigInt]): Unit = {
    super.poke(signal, value.reverse)
  }

  /**
    * expect fixes the superclass' method by reversing the value before passing
    * it on
    *
    * @param signal
    * @param expected
    * @return
    */
  override def expect (signal: Aggregate, expected: IndexedSeq[BigInt]): Boolean = {
    super.expect(signal, expected.reverse)
  }

  def expectResult(signal: Any, expected: Any): Boolean = {
    expect(signal == expected, s":: $signal should equal $expected ::")
  }

  def await(signal: Bool, timeout: Int = 100, negate: Boolean = false): Int = {
    var counter = 0
    val cmp = if (negate) 0 else 1
    while (peek(signal) != cmp) {
      counter += 1
      step(1)
      if (counter >= timeout) {
        expect(signal, 1, msg = "Timed out awaiting signal")
        return counter
      }
    }
    counter
  }
}
