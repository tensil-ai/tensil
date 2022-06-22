/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#include "instruction.h"

#include <string.h>

#include "architecture.h"

static void set_header(const struct tensil_instruction_layout *layout,
                       uint8_t *buffer, size_t offset, uint8_t opcode,
                       uint8_t flags) {
    size_t header_offset = offset + layout->operand0_size_bytes +
                           layout->operand1_size_bytes +
                           layout->operand2_size_bytes;

    buffer[header_offset] = (opcode << 4) | flags;
}

static void set_all_operands(const struct tensil_instruction_layout *layout,
                             uint8_t *buffer, size_t offset,
                             uint64_t operands) {
    for (size_t i = 0;
         i < (layout->operand0_size_bytes + layout->operand1_size_bytes +
              layout->operand2_size_bytes);
         i++)
        buffer[offset + i] = (operands >> (i * 8)) & 0xff;
}

static void set_operand0(const struct tensil_instruction_layout *layout,
                         uint8_t *buffer, size_t offset, uint64_t operand0) {
    size_t operand0_offset = offset;

    for (size_t i = 0; i < layout->operand0_size_bytes; i++)
        buffer[operand0_offset + i] = (operand0 >> (i * 8)) & 0xff;
}

static void set_operand1(const struct tensil_instruction_layout *layout,
                         uint8_t *buffer, size_t offset, uint64_t operand1) {
    size_t operand1_offset = offset + layout->operand0_size_bytes;

    for (size_t i = 0; i < layout->operand1_size_bytes; i++)
        buffer[operand1_offset + i] = (operand1 >> (i * 8)) & 0xff;
}

static void set_operand2(const struct tensil_instruction_layout *layout,
                         uint8_t *buffer, size_t offset, uint64_t operand2) {
    size_t operand2_offset =
        offset + layout->operand0_size_bytes + layout->operand1_size_bytes;

    for (size_t i = 0; i < layout->operand2_size_bytes; i++)
        buffer[operand2_offset + i] = (operand2 >> (i * 8)) & 0xff;
}

static size_t log2_ceil(size_t x) {
    int y = 0;

    while (x >>= 1)
        y += 1;

    return y;
}

static size_t round_size_bytes(size_t size) {
    size_t remainder = size % 8;
    if (remainder == 0)
        return (size / 8);

    return (size + 8 - remainder) / 8;
}

static size_t max_size(size_t x0, size_t x1) {
    if (x0 > x1)
        return x0;

    return x1;
}

static size_t max_size4(size_t x0, size_t x1, size_t x2, size_t x3) {
    return max_size(max_size(x0, x1), max_size(x2, x3));
}

static size_t min_size(size_t x0, size_t x1) {
    if (x0 < x1)
        return x0;

    return x1;
}

void tensil_instruction_layout_init(struct tensil_instruction_layout *layout,
                                    struct tensil_architecture *arch) {
    size_t local_operand_size_bits = log2_ceil(arch->local_depth);
    size_t accumulator_operand_size_bits = log2_ceil(arch->accumulator_depth);
    size_t dram0_operand_size_bits = log2_ceil(arch->dram0_depth);
    size_t dram1_operand_size_bits = log2_ceil(arch->dram1_depth);

    layout->stride0_size_bits = log2_ceil(arch->stride0_depth);
    layout->stride1_size_bits = log2_ceil(arch->stride1_depth);

    size_t simd_op_size_bits = log2_ceil(15);
    size_t simd_operand_size_bits = log2_ceil(arch->simd_registers_depth + 1);
    size_t simd_instruction_size_bits =
        simd_operand_size_bits * 3 + simd_op_size_bits;

    layout->operand0_address_size_bits =
        max_size(local_operand_size_bits,      // MatMul, DataMove, LoadWeights
                 accumulator_operand_size_bits // SIMD
        );

    layout->operand1_address_size_bits =
        max_size4(local_operand_size_bits,      // LoadWeights
                  dram0_operand_size_bits,      // DataMove
                  dram1_operand_size_bits,      // DataMove
                  accumulator_operand_size_bits // MatMul, DataMove, SIMD
        );

    size_t operand2_size_bits = max_size4(
        min_size(local_operand_size_bits,
                 accumulator_operand_size_bits), // MatMul, DataMove
        min_size(local_operand_size_bits, dram0_operand_size_bits), // DataMove
        min_size(local_operand_size_bits, dram1_operand_size_bits), // DataMove
        simd_instruction_size_bits                                  // SIMD
    );

    layout->header_size_bytes = 1;
    layout->operand0_size_bytes = round_size_bytes(
        layout->operand0_address_size_bits + layout->stride0_size_bits);
    layout->operand1_size_bytes = round_size_bytes(
        layout->operand1_address_size_bits + layout->stride1_size_bits);
    layout->operand2_size_bytes = round_size_bytes(operand2_size_bits);

    layout->instruction_size_bytes =
        layout->header_size_bytes + layout->operand0_size_bytes +
        layout->operand1_size_bytes + layout->operand2_size_bytes;
}

void tensil_instruction_set(const struct tensil_instruction_layout *layout,
                            uint8_t *buffer, size_t offset, uint8_t opcode,
                            uint8_t flags, uint64_t operand0, uint64_t operand1,
                            uint64_t operand2) {
    set_header(layout, buffer, offset, opcode, flags);
    set_operand0(layout, buffer, offset, operand0);
    set_operand1(layout, buffer, offset, operand1);
    set_operand2(layout, buffer, offset, operand2);
}

void tensil_instruction_set_all(const struct tensil_instruction_layout *layout,
                                uint8_t *buffer, size_t offset, uint8_t opcode,
                                uint8_t flags, uint64_t operands) {
    set_header(layout, buffer, offset, opcode, flags);
    set_all_operands(layout, buffer, offset, operands);
}

uint64_t
tensil_instruction_make_operand0(const struct tensil_instruction_layout *layout,
                                 uint64_t offset, uint64_t stride) {
    return ((stride & ((1 << layout->stride0_size_bits) - 1))
            << layout->operand0_address_size_bits) |
           (offset & ((1 << layout->operand0_address_size_bits) - 1));
}

uint64_t
tensil_instruction_make_operand1(const struct tensil_instruction_layout *layout,
                                 uint64_t offset, uint64_t stride) {
    return ((stride & ((1 << layout->stride1_size_bits) - 1))
            << layout->operand1_address_size_bits) |
           (offset & ((1 << layout->operand1_address_size_bits) - 1));
}
