# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

from tcu_pynq.util import log2_ceil, round_size_bits


class Layout:
    def __init__(self, arch):
        self.arch = arch

        self.header_size_bits = round_size_bits(8)

        self.local_operand_size_bits = log2_ceil(arch.local_depth)
        self.dram0_operand_size_bits = log2_ceil(arch.dram0_depth)
        self.dram1_operand_size_bits = log2_ceil(arch.dram1_depth)
        self.accumulator_operand_size_bits = log2_ceil(arch.accumulator_depth)

        self.stride0_size_bits = log2_ceil(arch.stride0_depth)
        self.stride1_size_bits = log2_ceil(arch.stride1_depth)

        self.simd_op_size_bits = 5  # Note this is hard-coded, need to be updated
        # if we add new SIMD ops
        self.simd_operand_size_bits = log2_ceil(arch.simd_registers_depth)
        self.simd_instruction_size_bits = (
            3 * self.simd_operand_size_bits + self.simd_op_size_bits
        )

        self.operand0_address_size_bits = max(
            self.local_operand_size_bits,
            self.accumulator_operand_size_bits,
        )
        self.operand0_size_bits = round_size_bits(
            self.operand0_address_size_bits + self.stride0_size_bits
        )

        self.operand1_address_size_bits = max(
            self.local_operand_size_bits,
            self.dram0_operand_size_bits,
            self.dram1_operand_size_bits,
            self.accumulator_operand_size_bits,
        )
        self.operand1_size_bits = round_size_bits(
            self.operand1_address_size_bits + self.stride1_size_bits
        )

        self.operand2_address_size_bits = max(
            min(self.local_operand_size_bits, self.accumulator_operand_size_bits),
            min(self.local_operand_size_bits, self.dram0_operand_size_bits),
            min(self.local_operand_size_bits, self.dram1_operand_size_bits),
            self.simd_instruction_size_bits,
        )
        self.operand2_size_bits = round_size_bits(self.operand2_address_size_bits)

        self.operands_size_bits = (
            self.operand0_size_bits + self.operand1_size_bits + self.operand2_size_bits
        )
        self.instruction_size_bits = self.header_size_bits + self.operands_size_bits
        self.instruction_size_bytes = self.instruction_size_bits // 8

        self.op_shift = self.operands_size_bits + 4
        self.flags_shift = self.operands_size_bits
        self.operand2_shift = self.operand0_size_bits + self.operand1_size_bits
        self.operand1_shift = self.operand0_size_bits
        self.operand0_shift = 0

    def __repr__(self):
        return "Layout({}, {}, {}, {})".format(
            self.instruction_size_bits,
            self.operand0_size_bits,
            self.operand1_size_bits,
            self.operand2_size_bits,
        )

    def instruction(self, op, flags, arg0, arg1, arg2):
        return (
            op << self.op_shift
            | flags << self.flags_shift
            | arg2 << self.operand2_shift
            | arg1 << self.operand1_shift
            | arg0 << self.operand0_shift
        )

    def no_op(self):
        return self.instruction(0x0, 0x0, 0, 0, 0)

    def matmul(self, accumulate, memory_address, accumulator_address, size):
        return self.instruction(
            0x1, accumulate, memory_address, accumulator_address, size
        )

    def data_move(self, flag, memory_address, accumulator_address, size):
        return self.instruction(0x2, flag, memory_address, accumulator_address, size)

    def load_weight(self, zeroes, weight_memory_address, size):
        return self.instruction(0x3, zeroes, weight_memory_address, size, 0)

    def simd(self, rwa, sub_instruction, acc_read_address, acc_write_address):
        return self.instruction(
            0x4, rwa, acc_write_address, acc_read_address, sub_instruction
        )

    def simd_sub(self, op, source_left, source_right, dest):
        return (
            op << (self.simd_operand_size_bits * 3)
            | source_left << (self.simd_operand_size_bits * 2)
            | source_right << self.simd_operand_size_bits
            | dest
        )

    def configure(self, register, value):
        return 0xF << self.op_shift | value << 4 | register

    def to_bytes(self, instruction):
        return instruction.to_bytes(self.instruction_size_bytes, "little")


class DataMoveFlag:
    dram0_to_memory = 0x0
    memory_to_dram0 = 0x1
    dram1_to_memory = 0x2
    memory_to_dram1 = 0x3
    # unused
    accumulator_to_memory = 0xC
    memory_to_accumulator = 0xD
    # reserved
    memory_to_accumulator_accumulate = 0xF


class SIMDFlag:
    none = 0x0
    read = 0x1
    write = 0x2
    accumulate = 0x4


class SIMDOp:
    zero = 0x1
    max = 0xF
