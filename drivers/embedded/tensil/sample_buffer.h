/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include "platform.h"

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "error.h"

#define TENSIL_SAMPLE_SIZE_BYTES 8
#define TENSIL_SAMPLE_INTERVAL_CYCLES 1000

struct tensil_sample_buffer {
    uint8_t *ptr;
    size_t size;
    size_t offset;
};

struct tensil_instruction_buffer;
struct tensil_instruction_layout;

void tensil_sample_buffer_reset(struct tensil_sample_buffer *sample_buffer);

const uint8_t *tensil_sample_buffer_find_valid_samples_ptr(
    const struct tensil_sample_buffer *sample_buffer);

bool tensil_sample_buffer_get_next_samples_ptr(
    const struct tensil_sample_buffer *sample_buffer,
    const struct tensil_instruction_buffer *instruction_buffer,
    const struct tensil_instruction_layout *layout, const uint8_t **ptr,
    uint32_t *program_counter, uint32_t *instruction_offset);

#ifdef TENSIL_PLATFORM_ENABLE_STDIO

tensil_error_t tensil_sample_buffer_print_analysis(
    const struct tensil_sample_buffer *sample_buffer,
    const struct tensil_instruction_buffer *instruction_buffer,
    const struct tensil_instruction_layout *layout, bool print_summary,
    bool print_aggregates, bool print_listing, uint32_t program_counter_shift);

#endif

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

tensil_error_t tensil_sample_buffer_to_file(
    const struct tensil_sample_buffer *sample_buffer,
    const struct tensil_instruction_buffer *instruction_buffer,
    const struct tensil_instruction_layout *layout, const char *file_name);

#endif

#endif
