/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include <stddef.h>
#include <stdint.h>

#include "error.h"

struct instruction_buffer {
    uint8_t *ptr;
    size_t offset;
    size_t size;
};

struct instruction_layout;

error_t buffer_append_instruction(struct instruction_buffer *buffer,
                                  const struct instruction_layout *layout,
                                  uint8_t opcode, uint8_t flags,
                                  uint64_t operand0, uint64_t operand1,
                                  uint64_t operand2);

error_t
buffer_append_config_instruction(struct instruction_buffer *buffer,
                                 const struct instruction_layout *layout,
                                 uint8_t reg, uint64_t value);

error_t buffer_append_noop_instructions(struct instruction_buffer *buffer,
                                        const struct instruction_layout *layout,
                                        size_t count);

error_t buffer_append_program(struct instruction_buffer *buffer,
                              const uint8_t *ptr, size_t size);

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

error_t buffer_append_program_from_file(struct instruction_buffer *buffer,
                                        size_t size, const char *file_name);

#endif

#ifdef TENSIL_PLATFORM_FLASH_READ

error_t buffer_append_program_from_flash(struct instruction_buffer *buffer,
                                         size_t size,
                                         TENSIL_PLATFORM_FLASH_TYPE flash);

#endif

error_t buffer_pad_to_alignment(struct instruction_buffer *buffer,
                                const struct instruction_layout *layout,
                                int alignment_bytes);

void buffer_reset(struct instruction_buffer *buffer);
