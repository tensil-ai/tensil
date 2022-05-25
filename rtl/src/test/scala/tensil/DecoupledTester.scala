/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

// import org.scalatest._
import chisel3.Module
import chiseltest.ChiselScalatestTester
import scala.collection.mutable
import chiseltest.internal.TesterThreadList
import chiseltest.fork
import firrtl.AnnotationSeq

trait DecoupledTester { spec: ChiselScalatestTester =>
  private val threads =
    new mutable.HashMap[String, mutable.ArrayBuffer[() => Unit]]

  def thread(name: String)(runnable: => Unit): Unit = {
    if (!threads.contains(name)) {
      threads += name -> new mutable.ArrayBuffer[() => Unit]
    }
    threads(name) += (() => runnable)
  }

  def decoupledTest[T <: Module](dut: => T): DecoupledTestBuilder[T] =
    new DecoupledTestBuilder(() => dut, Seq.empty)
  // new DecoupledTestBuilder(() => dut, Seq.empty, Array.empty)

  class DecoupledTestBuilder[T <: Module](
      val dutGen: () => T,
      val annotationSeq: AnnotationSeq,
      // val flags: Array[String]
  ) {
    def run(testFn: T => Unit): Unit = {
      val builder =
        new TestBuilder(dutGen, annotationSeq)
      // new TestBuilder(dutGen, annotationSeq) //, flags)
      builder.apply(main(testFn))
    }

    private def main(testFn: T => Unit)(dut: T): Unit = {
      threads.clear()
      testFn(dut)
      // println("DecoupledTester threads: " + threads.keys.mkString(", "))
      val forks = for ((name, segments) <- threads) yield {
        fork {
          for (segment <- segments) {
            segment()
          }
        }
      }
      forks.map(_.join())
    }

    def apply(testFn: T => Unit): Unit = run(testFn)
  }

  implicit class DecoupledTestOptionBuilder[T <: Module](
      x: DecoupledTestBuilder[T]
  ) {
    def withAnnotations(
        annotationSeq: AnnotationSeq
    ): DecoupledTestBuilder[T] = {
      new DecoupledTestBuilder[T](
        x.dutGen,
        x.annotationSeq ++ annotationSeq,
        // x.flags
      )
    }

    def withFlags(flags: Array[String]): DecoupledTestBuilder[T] = {
      new DecoupledTestBuilder[T](
        x.dutGen,
        x.annotationSeq,
        // x.flags ++ flags
      )
    }
  }
}
