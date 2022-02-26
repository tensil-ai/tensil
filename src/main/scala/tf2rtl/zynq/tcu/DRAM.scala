package tf2rtl.zynq.tcu

import java.io.{EOFException, InputStream}

import scala.collection.mutable
import scala.util.Random

import chisel3._
import chiseltest._

import tf2rtl.axi
import tf2rtl.DataTypeBase
import tf2rtl.Architecture
import tf2rtl.ArchitectureDataType
import tf2rtl.tools.compiler.{MemorySpan, MemoryAddressHelper}

object DRAM {
  def apply(
      arch: Architecture,
      index: Int,
      readDelayCycles: Int = 0,
      writeDelayCycles: Int = 0,
      debug: Boolean = false
  )(implicit
      axiConfig: axi.Config
  ): DRAM = {
    require(index >= 0 && index < 2)

    val depth = if (index == 0) arch.dram0Depth else arch.dram0Depth
    val name  = s"DRAM$index"

    arch.dataType.name match {
      case ArchitectureDataType.FLOAT32.name =>
        new DRAMWithBase(
          ArchitectureDataType.FLOAT32.base,
          arraySize = arch.arraySize,
          depth = depth,
          name = name,
          readDelayCycles = readDelayCycles,
          writeDelayCycles = writeDelayCycles,
          debug = debug
        )

      case ArchitectureDataType.FP32BP16.name =>
        new DRAMWithBase(
          ArchitectureDataType.FP32BP16.base,
          arraySize = arch.arraySize,
          depth = depth,
          name = name,
          readDelayCycles = readDelayCycles,
          writeDelayCycles = writeDelayCycles,
          debug = debug
        )

      case ArchitectureDataType.FP18BP10.name =>
        new DRAMWithBase(
          ArchitectureDataType.FP18BP10.base,
          arraySize = arch.arraySize,
          depth = depth,
          name = name,
          readDelayCycles = readDelayCycles,
          writeDelayCycles = writeDelayCycles,
          debug = debug
        )

      case ArchitectureDataType.FP16BP8.name =>
        new DRAMWithBase(
          ArchitectureDataType.FP16BP8.base,
          arraySize = arch.arraySize,
          depth = depth,
          name = name,
          readDelayCycles = readDelayCycles,
          writeDelayCycles = writeDelayCycles,
          debug = debug
        )

      case ArchitectureDataType.FP8BP4.name =>
        new DRAMWithBase(
          ArchitectureDataType.FP8BP4.base,
          arraySize = arch.arraySize,
          depth = depth,
          name = name,
          readDelayCycles = readDelayCycles,
          writeDelayCycles = writeDelayCycles,
          debug = debug
        )
    }
  }

}

abstract class DRAM {
  def dumpVectors(
      span: MemorySpan
  ): String =
    span.map(a => dumpVectors(a.raw)).mkString("\n")

  def dumpVectors(
      fromVectorAddress: Long,
      size: Long = 1L
  ): String

  def writeByte(address: Long, value: Byte): Unit
  def readByte(address: Long): Byte

  def writeFromStream(address: Int, stream: InputStream): Unit

  def writeBytes(address: Long, data: Array[Byte]): Unit
  def readBytes(address: Long, data: Array[Byte]): Unit

  def readFloatScalar(address: Long): Float
  def writeFloatScalar(address: Long, scalar: Float): Unit

  def readFloatVector(vectorAddress: Long): Array[Float]
  def writeFloatVector(vectorAddress: Long, vector: Seq[Float]): Unit

  def zero(): Unit
  def randomize(): Unit

  def listen(clock: Clock, port: axi.Master): Unit
}

class DRAMWithBase[T](
    val base: DataTypeBase[T],
    val arraySize: Int,
    val depth: Long,
    val name: String,
    readDelayCycles: Int,
    writeDelayCycles: Int,
    debug: Boolean
)(implicit val axiConfig: axi.Config)
    extends DRAM {
  val depthBytes         = depth * arraySize * base.sizeBytes
  val axiWidthBytes      = axiConfig.dataWidth / 8
  val scalarsPerAxiWidth = axiWidthBytes / base.sizeBytes

  private val mem: Array[Byte] = Array.fill(depthBytes.toInt)(0)

  case class Request(address: Int, length: Int, write: Boolean) {
    if (address >> 12 != (address + (length * axiWidthBytes) - 1) >> 12) {
      throw new Exception(
        s"A burst of length ${length}*${axiWidthBytes} at $address crossed a 4KB address boundary"
      )
    }

    override def toString(): String = {
      (if (write) "WriteReq" else "ReadReq") + s"(addr=$address, len=$length)"
    }
  }

  private val queue: mutable.Queue[Request] = mutable.Queue()

  private def checkAddress(address: Long): Unit = {
    if (address < 0 || address >= depthBytes) {
      throw new Exception(
        s"Address $address is out of range for $name of size $depthBytes"
      )
    }
  }

  def dumpVectors(
      fromVectorAddress: Long,
      size: Long
  ): String = {
    val lines =
      for (vectorAddress <- fromVectorAddress until fromVectorAddress + size)
        yield s"$vectorAddress\t=> ${readFloatVector(vectorAddress).map(v => f"$v%.4f").mkString(" ")}"

    lines.mkString("\n")
  }

  def writeByte(address: Long, value: Byte): Unit = {
    checkAddress(address)
    mem(address.toInt) = value
  }

  def readByte(address: Long): Byte = {
    checkAddress(address)
    mem(address.toInt)
  }

  def writeFromStream(address: Int, stream: InputStream): Unit = {
    var a = address
    var r = 0
    try {
      while (r >= 0) {
        checkAddress(a)
        r = stream.read(mem, a, stream.available())
        a += r
      }
    } catch {
      case _: EOFException =>
      case t: Throwable    => throw t
    } finally {
      stream.close()
    }
  }

  def writeBytes(address: Long, data: Array[Byte]): Unit = {
    for (i <- data.indices) {
      writeByte(address + i, data(i))
    }
  }

  def readBytes(address: Long, data: Array[Byte]): Unit = {
    for (i <- 0 until data.size) {
      data(i) = readByte(address + i)
    }
  }

  def readScalar(address: Long): T = {
    val buffer = Array.fill[Byte](base.sizeBytes)(0)
    readBytes(address, buffer)
    base.fromBytes(buffer)
  }

  def writeScalar(address: Long, scalar: T): Unit = {
    writeBytes(address, base.toBytes(scalar))
  }

  def readFloatScalar(address: Long): Float = {
    base.numeric.toFloat(readScalar(address))
  }

  def writeFloatScalar(address: Long, scalar: Float): Unit = {
    writeScalar(address, base.fromFloat(scalar))
  }

  def readFloatVector(vectorAddress: Long): Array[Float] = {
    val vector = for (i <- 0 until arraySize) yield {
      val address = (vectorAddress * arraySize + i) * base.sizeBytes
      readFloatScalar(address)
    }

    vector.toArray
  }

  def writeFloatVector(vectorAddress: Long, vector: Seq[Float]): Unit = {
    for (i <- 0 until arraySize) yield {
      val address = (vectorAddress * arraySize + i) * base.sizeBytes
      val scalar  = if (vector.isDefinedAt(i)) vector(i) else 0f
      writeFloatScalar(address, scalar)
    }
  }

  def zero(): Unit =
    for (i <- 0 until depthBytes.toInt)
      mem(i) = 0

  def randomize(): Unit =
    for (i <- 0 until depthBytes.toInt)
      mem(i) = Random.nextInt().toByte

  // scalastyle:off cyclomatic.complexity method.length
  def listen(clock: Clock, port: axi.Master): Unit = {
    fork {
      port.writeAddress.ready.poke(true.B)
      while (true) {
        if (port.writeAddress.valid.peek().litToBoolean) {
          val request = Request(
            port.writeAddress.bits.addr.peek().litValue().toInt,
            port.writeAddress.bits.len.peek().litValue().toInt + 1,
            write = true
          )

          if (debug) {
            println(s"$name:\tENQUEUE: $request")
          }

          queue += request
        }
        clock.step()
      }
    }

    fork {
      port.readAddress.ready.poke(true.B)
      while (true) {
        if (port.readAddress.valid.peek().litToBoolean) {
          val request = Request(
            port.readAddress.bits.addr.peek().litValue().toInt,
            port.readAddress.bits.len.peek().litValue().toInt + 1,
            write = false
          )

          if (debug) {
            println(s"$name:\tENQUEUE: $request")
          }

          queue += request
        }
        clock.step()
      }
    }

    fork {
      val buffer = Array.fill[Byte](axiWidthBytes)(0)

      def bytesToUInt(): UInt = {
        var v: BigInt = 0

        for (i <- 0 until buffer.size)
          v = (v << 8) | (BigInt(buffer(buffer.size - i - 1)) & 0xff)

        v.U
      }

      def uintToBytes(u: UInt): Array[Byte] = {
        var v = u.litValue()

        for (i <- 0 until buffer.size) {
          buffer(i) = (v & 0xff).toByte
          v = v >> 8
        }

        buffer
      }

      while (true) {
        port.writeData.ready.poke(false.B)
        port.readData.valid.poke(false.B)
        port.writeResponse.valid.poke(false.B)

        if (queue.nonEmpty) {
          val request = queue.dequeue()

          if (debug) {
            println(s"$name:\tHANDLE: $request")
          }

          if (request.write) {
            for (_ <- 0 until writeDelayCycles) {
              if (debug) {
                println(s"$name:\tDelaying write")
              }

              clock.step()
            }
          } else {
            for (_ <- 0 until readDelayCycles) {
              if (debug) {
                println(s"$name:\tDelaying read")
              }

              clock.step()
            }
          }

          for (i <- 0 until request.length) {
            val last    = (i == (request.length - 1))
            val address = request.address + i * axiWidthBytes

            // handle writes
            if (request.write) {
              port.writeData.ready.poke(true.B)
              // wait until data is valid
              while (!port.writeData.valid.peek().litToBoolean) {
                if (debug) {
                  println(s"$name:\tWaiting for valid")
                }

                clock.step()
              }

              // complete write
              writeBytes(address, uintToBytes(port.writeData.bits.data.peek()))
              if (debug) {
                val scalarsWritten =
                  for (k <- 0 until scalarsPerAxiWidth)
                    yield readFloatScalar(address + k * base.sizeBytes)

                println(
                  s"$name:\tWRITE[$address] = ${scalarsWritten.map(v => f"$v%.4f").mkString(" ")}" +
                    (if (last) " (last)" else "")
                )
              }
              clock.step()
              port.writeData.ready.poke(false.B)
              // if last data, emit write response
              if (last) {
                port.writeResponse.enqueue(axi.WriteResponse())
              }
            } else {
              if (debug) {
                val scalarsRead =
                  for (k <- 0 until scalarsPerAxiWidth)
                    yield readFloatScalar(address + k * base.sizeBytes)

                println(
                  s"$name:\tREAD[$address] = ${scalarsRead.map(v => f"$v%.4f").mkString(" ")}" +
                    (if (last) " (last)" else "")
                )
              }
              readBytes(address, buffer)
              port.readData.enqueue(
                axi.ReadData(
                  bytesToUInt(),
                  last.B
                )
              )
            }
          }
        }
        clock.step()
      }
    }
  }
  // scalastyle:on cyclomatic.complexity method.length
}
