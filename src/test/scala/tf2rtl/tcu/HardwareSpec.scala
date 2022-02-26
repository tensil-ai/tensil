package tf2rtl.tcu

import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._
import chisel3._
import chisel3.experimental.FixedPoint
import tf2rtl.{FunUnitSpec, Architecture, ArchitectureDataType}
import tf2rtl.tags.Hardware
import tf2rtl.tools.{Compiler, CompilerOptions}
import tf2rtl.util.floatToFixedPointBytes
import java.io.{
  ByteArrayOutputStream,
  DataOutputStream,
  FileInputStream,
  FileOutputStream,
  InputStream
}

class HardwareSpec extends FunUnitSpec {
  describe("Hardware") {
    describe("FixedPoint(16.W, 8.BP) on 4x4 array with m=256, w=256, a=256") {
      val arraySize        = 4
      val memoryDepth      = 256
      val accumulatorDepth = 256
      val constsDepth      = 256

      it("should run the XOR network", Hardware) {
        val graphFile  = "tf_models/xor.pb"
        val outputNode = "Identity"

        // TODO: need to flip each 32-bit word
        //  make a new PCIExxStream class to handle that for each direction
        val inputFifo   = new FileOutputStream("/dev/xillybus_input_32")
        val constsFifo  = new FileOutputStream("/dev/xillybus_consts_32")
        val programFifo = new FileOutputStream("/dev/xillybus_program_32")
        val outputFifo  = new FileInputStream("/dev/xillybus_output_32")

        val options = CompilerOptions(
          arch = Architecture.mkWithDefaults(
            dataType = ArchitectureDataType.FP16BP8,
            arraySize = arraySize,
            dram0Depth = memoryDepth,
            dram1Depth = constsDepth,
            accumulatorDepth = accumulatorDepth,
            localDepth = memoryDepth
          ),
          printSummary = true
        )

        val programStream = new ByteArrayOutputStream()
        val constsStream  = new ByteArrayOutputStream()
        val modelStream   = new FileInputStream(graphFile)

        Compiler.compileStreamToStreams(
          graphFile,
          Compiler.getModelSourceType(graphFile),
          modelStream,
          List(outputNode),
          programStream,
          constsStream,
          options
        )

        modelStream.close()

        val program = programStream.toByteArray
        val consts  = constsStream.toByteArray

        println(program.length)
        println(program.map(_.toHexString).mkString(", "))
        println(consts.length)
        println(consts.map(_.toHexString).mkString(", "))

        constsFifo.write(consts)
        programFifo.write(program)

        def f(data: Float*): Array[Byte] = {
          data
            .flatMap(
              floatToFixedPointBytes(
                _,
                16,
                8
              )
            )
            .toArray
        }

        println("writing input")
        inputFifo.write(f(0f, 0f, 0f, 0f))
        inputFifo.write(f(0f, 1f, 0f, 0f))
//        inputFifo.write(f(1f, 0f, 0f, 0f))
//        inputFifo.write(f(1f, 1f, 0f, 0f))

        println("reading output")
        def readWithTimeout(
            stream: InputStream,
            buf: Array[Byte],
            timeout: Int
        ): Int = {
          val read = Future {
            stream.read(buf)
          }.recover({
            case _: Throwable => {
              stream.close()
              -1
            }
          })
          Await.result(read, timeout.seconds)
        }

        val outputBuffer = new Array[Byte](8)
        val read         = readWithTimeout(outputFifo, outputBuffer, 10)
//        val read         = outputFifo.read(outputBuffer)
        println(outputBuffer.map(_.toHexString).mkString(", "))

        inputFifo.close()
        constsFifo.close()
        programFifo.close()
        outputFifo.close()
      }
    }
  }
}
