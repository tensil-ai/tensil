/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include <stddef.h>
#include <stdint.h>

#define TENSIL_OPCODE_NOOP 0x0
#define TENSIL_OPCODE_MAT_MUL 0x1
#define TENSIL_OPCODE_DATA_MOVE 0x2
#define TENSIL_OPCODE_LOAD_WEIGHT 0x3
#define TENSIL_OPCODE_SIMD 0x4
#define TENSIL_OPCODE_CONFIG 0xf

#define TENSIL_DATA_MOVE_FLAG_DRAM0_TO_LOCAL 0b0000
#define TENSIL_DATA_MOVE_FLAG_LOCAL_TO_DRAM0 0b0001
#define TENSIL_DATA_MOVE_FLAG_DRAM1_TO_LOCAL 0b0010
#define TENSIL_DATA_MOVE_FLAG_LOCAL_TO_DRAM1 0b0011

#define TENSIL_DATA_MOVE_FLAG_ACC_TO_LOCAL 0b1100
#define TENSIL_DATA_MOVE_FLAG_LOCAL_TO_ACC 0b1101
#define TENSIL_DATA_MOVE_FLAG_LOCAL_TO_ACC_WITH_ACC 0b1111

#define TENSIL_LOAD_WEIGHT_FLAG_ZEROES 0b1

#define TENSIL_MAT_MUL_FLAG_ACC 0b01
#define TENSIL_MAT_MUL_FLAG_ZEROES 0b10

#define TENSIL_SIMD_FLAG_READ 0b001
#define TENSIL_SIMD_FLAG_WRITE 0b010
#define TENSIL_SIMD_FLAG_ACC 0b100

#define TENSIL_SIMD_OPCODE_MOVE 0x2
#define TENSIL_SIMD_OPCODE_ADD 0x8
#define TENSIL_SIMD_OPCODE_MUL 0xa
// TODO: add remaining SIMD opcodes

#define TENSIL_CONFIG_REGISTER_DRAM0_OFFSET 0x0
#define TENSIL_CONFIG_REGISTER_DRAM1_OFFSET 0x4
#define TENSIL_CONFIG_REGISTER_TIMEOUT 0x8
#define TENSIL_CONFIG_REGISTER_PROGRAM_COUNTER 0xa
#define TENSIL_CONFIG_REGISTER_SAMPLE_INTERVAL 0xb

#define TENSIL_CONFIG_DRAM_OFFSET(ptr) ((size_t)ptr >> 16)

struct tensil_instruction_layout {
    size_t header_size_bytes;
    size_t operand0_size_bytes;
    size_t operand1_size_bytes;
    size_t operand2_size_bytes;
    size_t instruction_size_bytes;

    size_t stride0_size_bits;
    size_t stride1_size_bits;
    size_t operand0_address_size_bits;
    size_t operand1_address_size_bits;
};

struct tensil_architecture;

void tensil_instruction_layout_init(struct tensil_instruction_layout *layout,
                                    struct tensil_architecture *arch);

void tensil_instruction_set(const struct tensil_instruction_layout *layout,
                            uint8_t *buffer, size_t offset, uint8_t opcode,
                            uint8_t flags, uint64_t operand0, uint64_t operand1,
                            uint64_t operand2);

void tensil_instruction_set_all(const struct tensil_instruction_layout *layout,
                                uint8_t *buffer, size_t offset, uint8_t opcode,
                                uint8_t flags, uint64_t operands);

uint64_t
tensil_instruction_make_operand0(const struct tensil_instruction_layout *layout,
                                 uint64_t offset, uint64_t stride);

uint64_t
tensil_instruction_make_operand1(const struct tensil_instruction_layout *layout,
                                 uint64_t offset, uint64_t stride);