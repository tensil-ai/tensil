/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include <stddef.h>
#include <stdint.h>

#define OPCODE_NOOP 0x0
#define OPCODE_MAT_MUL 0x1
#define OPCODE_DATA_MOVE 0x2
#define OPCODE_LOAD_WEIGHT 0x3
#define OPCODE_SIMD 0x4
#define OPCODE_CONFIG 0xf

#define DATA_MOVE_FLAG_DRAM0_TO_LOCAL 0b0000
#define DATA_MOVE_FLAG_LOCAL_TO_DRAM0 0b0001
#define DATA_MOVE_FLAG_DRAM1_TO_LOCAL 0b0010
#define DATA_MOVE_FLAG_LOCAL_TO_DRAM1 0b0011

#define DATA_MOVE_FLAG_ACC_TO_LOCAL 0b1100
#define DATA_MOVE_FLAG_LOCAL_TO_ACC 0b1101
#define DATA_MOVE_FLAG_LOCAL_TO_ACC_WITH_ACC 0b1111

#define LOAD_WEIGHT_FLAG_ZEROES 0b1

#define MAT_MUL_FLAG_ACC 0b01
#define MAT_MUL_FLAG_ZEROES 0b10

#define SIMD_FLAG_READ 0b001
#define SIMD_FLAG_WRITE 0b010
#define SIMD_FLAG_ACC 0b100

#define SIMD_OPCODE_MOVE 0x2
#define SIMD_OPCODE_ADD 0x8
#define SIMD_OPCODE_MUL 0xa
// TODO: add remaining SIMD opcodes

#define CONFIG_REGISTER_DRAM0_OFFSET 0x0
#define CONFIG_REGISTER_DRAM1_OFFSET 0x4
#define CONFIG_REGISTER_TIMEOUT 0x8
#define CONFIG_REGISTER_PROGRAM_COUNTER 0xa
#define CONFIG_REGISTER_SAMPLE_INTERVAL 0xb

#define CONFIG_DRAM_OFFSET(ptr) ((size_t)ptr >> 16)

struct instruction_layout {
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

struct architecture;

void instruction_layout_init(struct instruction_layout *layout,
                             struct architecture *arch);

void instruction_set(const struct instruction_layout *layout, uint8_t *buffer,
                     size_t offset, uint8_t opcode, uint8_t flags,
                     uint64_t operand0, uint64_t operand1, uint64_t operand2);

void instruction_set_all(const struct instruction_layout *layout,
                         uint8_t *buffer, size_t offset, uint8_t opcode,
                         uint8_t flags, uint64_t operands);

uint64_t instruction_make_operand0(const struct instruction_layout *layout,
                                   uint64_t offset, uint64_t stride);

uint64_t instruction_make_operand1(const struct instruction_layout *layout,
                                   uint64_t offset, uint64_t stride);