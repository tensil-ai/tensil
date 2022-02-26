package tf2rtl.tools

import scala.collection.mutable

class RMSE(printValues: Boolean = true) {
  private val ses = mutable.ArrayBuffer.empty[Double]

  def addSample(y: Float, yExpected: Float) = {
    if (printValues)
      println(s"expected = $yExpected, actual = $y")

    ses += Math.pow(yExpected - y, 2)
  }

  def compute = {
    val rmse = Math.sqrt(ses.sum / ses.size)
    println(s"rmse = $rmse")
    rmse
  }
}
