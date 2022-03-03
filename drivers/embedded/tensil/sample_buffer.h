/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include "platform.h"

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "error.h"

#define SAMPLE_SIZE_BYTES 32
#define SAMPLE_INTERVAL_CYCLES 1000

struct sample_buffer {
    uint8_t *ptr;
    size_t size;
    size_t offset;
};

struct instruction_buffer;
struct instruction_layout;

void sample_buffer_reset(struct sample_buffer *sample_buffer);

void sample_buffer_before_read(const struct sample_buffer *sample_buffer);

error_t sample_buffer_print_analysis(
    const struct sample_buffer *sample_buffer,
    const struct instruction_buffer *instruction_buffer,
    const struct instruction_layout *layout, bool print_summary,
    bool print_aggregates, bool print_listing, uint32_t program_counter_shift);

#endif
