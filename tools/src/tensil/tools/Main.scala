package tensil.tools

import java.io.{DataOutputStream, FileOutputStream}
import tensil.{Architecture, ArchitectureDataType, InstructionLayout}

object Main extends App {
  val dataType = ArchitectureDataType.FP16BP8
  val arch     = Architecture.tiny
  // val model    = "yolov4_tiny_192"
  val model = "resnet20v2_cifar"
  // val model      = "xor"
  val outputNodes = Array("Identity")
  // val outputNodes = Array(
  //   "model/conv2d_17/BiasAdd",
  //   "model/conv2d_20/BiasAdd",
  // )

  run(
    model,
    outputNodes,
  )

  // scalastyle:off method.length
  def run(
      model: String,
      outputNodes: Seq[String],
  ): Unit = {
    val graphFile = s"tf_models/$model.pb"

    val options = CompilerOptions(
      arch = arch,
      inputBatchSize = 1,
      printSummary = true,
    )

    println()
    println(
      s"Compiling $graphFile for systolic array arch with design parameters:"
    )
    println(s"  data type            = $dataType")
    println(s"  arch                 = $arch")

    Compiler.compile(
      model,
      graphFile,
      outputNodes.toList,
      options
    )

    println()
    println("Generated artifacts:")

    println()
    println(" DONE")
  }
  // scalastyle:on method.length
}
