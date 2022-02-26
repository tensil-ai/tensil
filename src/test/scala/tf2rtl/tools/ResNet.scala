package tf2rtl.tools

import java.io._
import scala.reflect.ClassTag
import scala.io.Source
import tf2rtl.tools.golden.{Processor, ExecutiveTraceContext}
import tf2rtl.ArchitectureDataType

object ResNet {
  def prepareInputStream(
      dataType: ArchitectureDataType,
      arraySize: Int,
      count: Int
  ): InputStream = {
    val fileName = s"tf_models/resnet_input_${count}x32x32x${arraySize}.csv"

    val inputPrep           = new ByteArrayOutputStream()
    val inputPrepDataStream = new DataOutputStream(inputPrep)

    Util.writeCsv(
      dataType,
      inputPrepDataStream,
      arraySize,
      fileName
    )

    new ByteArrayInputStream(inputPrep.toByteArray())
  }

  val ClassSize = 10
  val GoldenClasses: Array[Int] = Array(
    0, 9, 5, 7, 9, 8, 5, 7, 8, 6
  )

  def assertOutput(
      dataType: ArchitectureDataType,
      arraySize: Int,
      bytes: Array[Byte],
      count: Int
  ): Unit = {
    val output =
      new DataInputStream(new ByteArrayInputStream(bytes))

    for (i <- 0 until count) {
      assert(
        Util.argMax(
          Util.readResult(dataType, output, arraySize, ClassSize)
        ) == GoldenClasses(i)
      )
    }
  }
}
