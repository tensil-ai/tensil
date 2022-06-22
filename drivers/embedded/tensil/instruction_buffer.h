/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include <stddef.h>
#include <stdint.h>

#include "error.h"

struct tensil_instruction_buffer {
    uint8_t *ptr;
    size_t offset;
    size_t size;
};

struct tensil_instruction_layout;

tensil_error_t tensil_buffer_append_instruction(
    struct tensil_instruction_buffer *buffer,
    const struct tensil_instruction_layout *layout, uint8_t opcode,
    uint8_t flags, uint64_t operand0, uint64_t operand1, uint64_t operand2);

tensil_error_t tensil_buffer_append_config_instruction(
    struct tensil_instruction_buffer *buffer,
    const struct tensil_instruction_layout *layout, uint8_t reg,
    uint64_t value);

tensil_error_t tensil_buffer_append_noop_instructions(
    struct tensil_instruction_buffer *buffer,
    const struct tensil_instruction_layout *layout, size_t count);

tensil_error_t
tensil_buffer_append_program(struct tensil_instruction_buffer *buffer,
                             const uint8_t *ptr, size_t size);

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

tensil_error_t
tensil_buffer_append_program_from_file(struct tensil_instruction_buffer *buffer,
                                       size_t size, const char *file_name);

#endif

tensil_error_t
tensil_buffer_pad_to_alignment(struct tensil_instruction_buffer *buffer,
                               const struct tensil_instruction_layout *layout,
                               int alignment_bytes);

void tensil_buffer_reset(struct tensil_instruction_buffer *buffer);
