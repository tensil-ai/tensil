package tensil.data

import chisel3._
import tensil.tcu.instruction.Instruction

import java.io.InputStream

class InstructionReader(
    stream: InputStream,
    instructionSizeBytes: Int,
) extends Iterator[BigInt] {
  val readLength            = instructionSizeBytes
  private val bytes         = new Array[Byte](readLength)
  private var lastRead: Int = stream.read(bytes, 0, readLength)

  def hasNext: Boolean =
    lastRead != -1

  def next(): BigInt = {
    val v = BigInt(bytes.reverse)

    lastRead = stream.read(bytes, 0, readLength)

    v
  }
}
