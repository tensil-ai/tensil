/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import chiseltest._
import tensil.{Architecture, FunUnitSpec}
import tensil.InstructionLayout
import tensil.tcu.instruction.{
  Instruction,
  MatMulArgs,
  MatMulFlags,
  Opcode,
  SIMDArgs,
  SIMDFlags
}

class ValidatorSpec extends FunUnitSpec {
  describe("Validator") {
    describe("when") {
      implicit val layout = new InstructionLayout(
        Architecture.mkWithDefaults(
          arraySize = 8,
          dram0Depth = 1048576,
          dram1Depth = 1048576,
          localDepth = 2048,
          accumulatorDepth = 512,
          simdRegistersDepth = 1,
          stride0Depth = 1,
          stride1Depth = 1
        )
      )
      it("should not error when instruction is not valid") {
        test(new Validator(layout)) { m =>
          m.io.instruction.valid.poke(false.B)
          m.io.instruction.bits.poke(
            Instruction(
              Opcode.MatMul,
              MatMulFlags(false, false),
              MatMulArgs(0, 0, 2048)
            )
          )
          m.io.error.expect(false.B)
        }
      }
      it("should not error on valid size") {
        test(new Validator(layout)) { m =>
          m.io.instruction.valid.poke(true.B)
          m.io.instruction.bits.poke(
            Instruction(
              Opcode.MatMul,
              MatMulFlags(false, false),
              MatMulArgs(0, 0, 2047)
            )
          )
          m.io.error.expect(true.B)
        }
      }
      it("should error on invalid size") {
        test(new Validator(layout)) { m =>
          m.io.instruction.valid.poke(true.B)
          m.io.instruction.bits.poke(
            Instruction(
              Opcode.MatMul,
              MatMulFlags(false, false),
              MatMulArgs(0, 0, 2048)
            )
          )
          m.io.error.expect(true.B)
        }
      }
      it(
        "should error on SIMD when acc read is indicated but neither source is input"
      ) {
        test(new Validator(layout)) { m =>
          m.io.instruction.valid.poke(true.B)
          m.io.instruction.bits.poke(
            Instruction(
              Opcode.SIMD,
              SIMDFlags(true, false, false),
              SIMDArgs(
                0,
                0,
                simd.Instruction(
                  simd.Op.Add,
                  1,
                  1,
                  0,
                )
              ),
            )
          )
          m.io.error.expect(true.B)
        }
      }
    }
  }
}
