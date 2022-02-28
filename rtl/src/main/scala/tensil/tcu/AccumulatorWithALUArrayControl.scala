package tensil.tcu

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util.log2Ceil
import tensil.tcu.instruction.Box
import tensil.tcu.simd.Instruction
import tensil.Architecture
import tensil.mem.Address
import tensil.mem.Size
import tensil.mem.Stride
import tensil.mem.Reverse
import tensil.InstructionLayout

class AccumulatorWithALUArrayControl(
    val layout: InstructionLayout
) extends Bundle {
  val instruction  = new Instruction(layout)
  val readAddress  = UInt(log2Ceil(layout.arch.accumulatorDepth).W)
  val writeAddress = UInt(log2Ceil(layout.arch.accumulatorDepth).W)
  val accumulate   = Bool()
  val write        = Bool()
  val read         = Bool()
}

object AccumulatorWithALUArrayControl {
  def apply(
      instruction: Instruction,
      readAddress: BigInt,
      writeAddress: BigInt,
      read: Boolean,
      write: Boolean,
      accumulate: Boolean
  )(implicit arch: Architecture): AccumulatorWithALUArrayControl = {
    val layout = InstructionLayout(arch)
    new AccumulatorWithALUArrayControl(
      layout
    ).Lit(
      _.instruction  -> instruction,
      _.readAddress  -> readAddress.U,
      _.writeAddress -> writeAddress.U,
      _.read         -> read.B,
      _.write        -> write.B,
      _.accumulate   -> accumulate.B,
    )
  }

  def read(
      address: BigInt
  )(implicit arch: Architecture): AccumulatorWithALUArrayControl = {
    implicit val layout = InstructionLayout(arch)
    AccumulatorWithALUArrayControl(
      simd.Instruction.noOp(),
      address,
      0,
      true,
      false,
      false
    )
  }

  def write(
      address: BigInt,
      accumulate: Boolean = false
  )(implicit arch: Architecture): AccumulatorWithALUArrayControl = {
    implicit val layout = InstructionLayout(arch)
    AccumulatorWithALUArrayControl(
      simd.Instruction.noOp(),
      0,
      address,
      false,
      true,
      accumulate
    )
  }
}

class AccumulatorMemControl(
    val layout: InstructionLayout,
) extends Bundle
    with Address {
  val instruction = new Instruction(layout)
  val address     = UInt(log2Ceil(layout.arch.accumulatorDepth).W)
  val altAddress  = UInt(log2Ceil(layout.arch.accumulatorDepth).W)
  val read        = Bool()
  val write       = Bool()
  val accumulate  = Bool()

  def toAccumulatorWithALUArrayControl()(implicit
      arch: Architecture
  ): AccumulatorWithALUArrayControl = {
    val layout = InstructionLayout(arch)
    val w = Wire(
      new AccumulatorWithALUArrayControl(
        layout
      )
    )
    val isMemControl = instruction.op === simd.Op.NoOp.U
    w.instruction := instruction
    w.read := read
    w.write := write
    w.accumulate := accumulate
    when(isMemControl) {
      when(read) {
        w.readAddress := address
        w.writeAddress := altAddress
      }.otherwise {
        when(write) {
          w.readAddress := altAddress
          w.writeAddress := address
        }.otherwise {
          w.readAddress := address
          w.writeAddress := altAddress
        }
      }
    }.otherwise {
      w.readAddress := address
      w.writeAddress := altAddress
    }
    w
    // }
  }
}

class AccumulatorMemControlWithSizeWithStride(
    layout: InstructionLayout
) extends AccumulatorMemControl(layout)
    with Size
    with Stride
    with Reverse {
  val size    = UInt(log2Ceil(layout.arch.accumulatorDepth).W)
  val stride  = UInt(log2Ceil(layout.arch.stride1Depth).W)
  val reverse = Bool()
}
