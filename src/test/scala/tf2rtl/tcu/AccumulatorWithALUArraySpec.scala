package tf2rtl.tcu

import chisel3._
import chiseltest._
import chiseltest.internal.TesterThreadList
import chisel3.experimental.BundleLiterals._
import tf2rtl.UnitSpec
import tf2rtl.decoupled.{decoupledToDriver, decoupledVecToDriver}
import scala.reflect.ClassTag
import scala.collection.mutable
import tf2rtl.Architecture
import tf2rtl.tools.compiler.InstructionLayout

class AccumulatorWithALUArraySpec extends UnitSpec {
  behavior of "AccumulatorWithALUArray"

  it should "handle large volume of writes" in {
    val gen = SInt(8.W)
    implicit val arch = Architecture.mkWithDefaults(
      arraySize = 8,
      accumulatorDepth = 32,
    )
    decoupledTest(new AccumulatorWithALUArray(gen, arch)) { m =>
      val n = 10

      val threads = new mutable.ArrayBuffer[TesterThreadList]

      m.io.control.setSourceClock(m.clock)
      m.io.input.setSourceClock(m.clock)
      m.io.output.setSinkClock(m.clock)

      for (_ <- 0 until n) {
        for (i <- 0 until arch.accumulatorDepth.toInt) {
          m.write(i)
        }
      }

      thread("input") {
        for (_ <- 0 until n) {
          for (i <- 0 until arch.accumulatorDepth.toInt) {
            m.io.input.enqueue(Array.fill(arch.arraySize)(i.S))
          }
        }
      }
    }
  }

  it should "relu" in {
    val gen          = SInt(8.W)
    val height       = 8
    val length       = 8
    val numOps       = simd.Op.numOps
    val numRegisters = 1
    implicit val arch = Architecture.mkWithDefaults(
      arraySize = height,
      accumulatorDepth = length,
      simdRegistersDepth = numRegisters
    )
    implicit val layout = InstructionLayout(arch)
    decoupledTest(
      new AccumulatorWithALUArray(
        gen,
        arch,
      )
    ) { m =>
      m.clock.setTimeout(20)

      def relu(x: Int): Int = Math.max(x, 0)

      val data     = Array(0, 1, 2, 3, 0, -1, -2, -3)
      val expected = data.map(relu)
      m.setClocks()

      // write in data
      m.write(0)
      thread("input") {
        m.io.input.enqueue(data.map(_.S))
      }

//      m.noop(10)

      // zero the register (dest = 1 meaning the first register)
      m.command(
        simd.Instruction(simd.Op.Zero, 0, 0, 1),
        0,
        0,
        false,
        false,
        false
      )

      // max the input data with the zero in the register to compute a ReLU
      // do not store the result in the register (dest = 0 meaning output only)
      // read data from acc address 0 into alu, write back to acc address 1
      m.command(
        simd.Instruction(simd.Op.Max, 0, 1, 0),
        0,
        1,
        true,
        true,
        false
      )

      // delay the read a few cycles and give the data from the ALUs time to
      // write into the accumulators
      m.noop(3)

      // read data out from acc
      m.read(1)

      thread("output") {
        m.io.output.expectDequeue(expected.map(_.S))
      }
    }
  }

  it should "maxpool" in {
    val gen          = SInt(8.W)
    val height       = 8
    val length       = 8
    val numOps       = simd.Op.numOps
    val numRegisters = 1
    implicit val arch = Architecture.mkWithDefaults(
      arraySize = height,
      accumulatorDepth = length,
      simdRegistersDepth = numRegisters
    )
    implicit val layout = InstructionLayout(arch)
    decoupledTest(
      new AccumulatorWithALUArray(
        gen,
        arch,
      )
    ) { m =>
      val data = Array(
        Array(1, 2, 3, 4),
        Array(4, 3, 2, 1),
        Array(-1, 0, 1, 2),
        Array(2, 1, 0, -1),
        Array(-1, -1, -2, -1),
        Array(1, -1, 2, -2),
        Array(1, 0, 0, 0),
        Array(-1, 0, 0, 0)
      )
      val expected = data.map(_.max)

      m.setClocks()

      // write in data
      for (i <- 0 until 4) {
        m.write(i)
        thread("input") {
          m.io.input.enqueue(data.map(_(i).S))
        }
      }

      // max the input data with itself (since the data in the register is not
      // valid yet). Store the result in the firt register (dest = 1)
      m.command(
        simd.Instruction(simd.Op.Max, 0, 0, 1),
        0,
        0,
        true,
        false,
        false
      )
      for (i <- 1 until 3) {
        // max the input data with the contents of the first register, and store
        // the result in the first register
        m.command(
          simd.Instruction(simd.Op.Max, 0, 1, 1),
          i,
          0,
          true,
          false,
          false
        )
      }
      // max again, and then write the final result to address 4
      m.command(simd.Instruction(simd.Op.Max, 0, 1, 1), 3, 4, true, true, false)

      // delay the read a few cycles and give the data from the ALUs time to
      // write into the accumulators
      m.noop(10)
//      m.noop()

      // read data out from acc
      m.read(4)

      thread("output") {
        m.io.output.expectDequeue(expected.map(_.S))
      }
    }
  }

  // accumulator tests
  it should "read and write values" in {
    val height       = 8
    val length       = 8
    val numOps       = simd.Op.numOps
    val numRegisters = 1
    implicit val arch = Architecture.mkWithDefaults(
      arraySize = height,
      accumulatorDepth = length,
      simdRegistersDepth = numRegisters
    )
    implicit val layout = InstructionLayout(arch)
    decoupledTest(
      new AccumulatorWithALUArray(
        UInt(8.W),
        arch
      )
    ) { m =>
      val testData = for (i <- 0 until height) yield i.U

      m.setClocks()

      m.write(0)
      thread("input") {
        m.io.input.enqueue(testData)
      }

      m.read(0)
      thread("output") {
        m.io.output.expectDequeue(testData)
      }
    }
  }

  it should "accumulate values" in {
    val height       = 8
    val length       = 8
    val numOps       = simd.Op.numOps
    val numRegisters = 1
    implicit val arch = Architecture.mkWithDefaults(
      arraySize = height,
      accumulatorDepth = length,
      simdRegistersDepth = numRegisters
    )
    implicit val layout = InstructionLayout(arch)
    decoupledTest(
      new AccumulatorWithALUArray(
        UInt(8.W),
        arch
      )
    ) { m =>
      val testData   = for (i <- 0 until height) yield i.U
      val expectData = for (i <- 0 until height) yield (2 * i).U

      m.setClocks()

      m.write(0)
      thread("input") {
        m.io.input.enqueue(testData)
      }

      m.read(0)
      thread("output") {
        m.io.output.expectDequeue(testData)
      }

      m.accumulate(0)
      thread("input") {
        m.io.input.enqueue(testData)
      }

      m.read(0)
      thread("output") {
        m.io.output.expectDequeue(expectData)
      }
    }
  }

  implicit class AccumulateWithALUArrayHelper[T <: Data with Num[T] : ClassTag](
      m: AccumulatorWithALUArray[T]
  ) {
    implicit val layout = m.layout

    def setClocks(): Unit = {
      m.io.control.setSourceClock(m.clock)
      m.io.input.setSourceClock(m.clock)
      m.io.output.setSinkClock(m.clock)
    }

    def command(
        instruction: simd.Instruction,
        readAddress: BigInt,
        writeAddress: BigInt,
        read: Boolean,
        write: Boolean,
        accumulate: Boolean
    ): Unit = {
      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(
              _.instruction  -> instruction,
              _.readAddress  -> readAddress.U,
              _.writeAddress -> writeAddress.U,
              _.read         -> read.B,
              _.write        -> write.B,
              _.accumulate   -> accumulate.B
            )
        )
      }
    }

    def read(address: BigInt): Unit = {
      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(
              _.instruction  -> simd.Instruction(simd.Op.NoOp, 0, 0, 0),
              _.readAddress  -> address.U,
              _.writeAddress -> 0.U,
              _.read         -> true.B,
              _.write        -> false.B,
              _.accumulate   -> false.B
            )
        )
      }
    }

    def write(address: BigInt): Unit = {
      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(
              _.instruction  -> simd.Instruction(simd.Op.NoOp, 0, 0, 0),
              _.readAddress  -> 0.U,
              _.writeAddress -> address.U,
              _.read         -> false.B,
              _.write        -> true.B,
              _.accumulate   -> false.B
            )
        )
      }
    }

    def accumulate(address: BigInt): Unit = {
      thread("control") {
        m.io.control.enqueue(
          chiselTypeOf(m.io.control.bits)
            .Lit(
              _.instruction  -> simd.Instruction(simd.Op.NoOp, 0, 0, 0),
              _.readAddress  -> 0.U,
              _.writeAddress -> address.U,
              _.read         -> false.B,
              _.write        -> true.B,
              _.accumulate   -> true.B
            )
        )
      }
    }

    def noop(repeat: Int = 1): Unit = {
      for (_ <- 0 until repeat) {
        m.command(
          simd.Instruction(simd.Op.NoOp, 0, 0, 0),
          0,
          0,
          false,
          false,
          false
        )
      }
    }
  }
}
