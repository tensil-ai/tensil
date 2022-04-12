/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include "platform.h"

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "error.h"

#define SAMPLE_SIZE_BYTES 8
#define SAMPLE_INTERVAL_CYCLES 1000

struct sample_buffer {
    uint8_t *ptr;
    size_t size;
    size_t offset;
};

struct instruction_buffer;
struct instruction_layout;

void sample_buffer_reset(struct sample_buffer *sample_buffer);

const uint8_t *
sample_buffer_find_valid_samples_ptr(const struct sample_buffer *sample_buffer);

bool sample_buffer_get_next_samples_ptr(
    const struct sample_buffer *sample_buffer,
    const struct instruction_buffer *instruction_buffer,
    const struct instruction_layout *layout, const uint8_t **ptr,
    uint32_t *program_counter, uint32_t *instruction_offset);

error_t sample_buffer_print_analysis(
    const struct sample_buffer *sample_buffer,
    const struct instruction_buffer *instruction_buffer,
    const struct instruction_layout *layout, bool print_summary,
    bool print_aggregates, bool print_listing, uint32_t program_counter_shift);

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

error_t
sample_buffer_to_file(const struct sample_buffer *sample_buffer,
                      const struct instruction_buffer *instruction_buffer,
                      const struct instruction_layout *layout,
                      const char *file_name);

#endif

#endif
