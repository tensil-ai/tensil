package tensil

// import org.scalatest._
import chiseltest.ChiselScalatestTester
import chisel3.MultiIOModule
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

  def decoupledTest[T <: MultiIOModule](dut: => T): DecoupledTestBuilder[T] =
    new DecoupledTestBuilder(() => dut, Seq.empty, Array.empty)

  class DecoupledTestBuilder[T <: MultiIOModule](
      val dutGen: () => T,
      val annotationSeq: AnnotationSeq,
      val flags: Array[String]
  ) {
    def run(testFn: T => Unit): Unit = {
      val builder =
        new TestBuilder(dutGen, annotationSeq, flags)
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

  implicit class DecoupledTestOptionBuilder[T <: MultiIOModule](
      x: DecoupledTestBuilder[T]
  ) {
    def withAnnotations(
        annotationSeq: AnnotationSeq
    ): DecoupledTestBuilder[T] = {
      new DecoupledTestBuilder[T](
        x.dutGen,
        x.annotationSeq ++ annotationSeq,
        x.flags
      )
    }

    def withFlags(flags: Array[String]): DecoupledTestBuilder[T] = {
      new DecoupledTestBuilder[T](
        x.dutGen,
        x.annotationSeq,
        x.flags ++ flags
      )
    }
  }
}
