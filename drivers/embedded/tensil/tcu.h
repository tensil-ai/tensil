/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "platform.h"

#if defined(TENSIL_PLATFORM_INSTRUCTION_AXI_DMA_DEVICE_ID) ||                  \
    defined(TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID)
#include "xaxidma.h"
#endif

#include "error.h"

struct tensil_compute_unit {
#ifdef TENSIL_PLATFORM_INSTRUCTION_AXI_DMA_DEVICE_ID
    XAxiDma instruction_axi_dma;
#endif
#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    XAxiDma sample_axi_dma;
    size_t sample_block_size;
#endif
};

struct tensil_sample_buffer;
struct tensil_instruction_buffer;

#ifdef TENSIL_PLATFORM_INSTRUCTION_AXI_DMA_DEVICE_ID

tensil_error_t tensil_compute_unit_init(struct tensil_compute_unit *tcu);

tensil_error_t tensil_compute_unit_start_instructions(
    struct tensil_compute_unit *tcu,
    const struct tensil_instruction_buffer *buffer, size_t *run_offset);

bool tensil_compute_unit_is_instructions_busy(struct tensil_compute_unit *tcu);

int tensil_compute_unit_get_instructions_data_width_bytes(
    struct tensil_compute_unit *tcu);

#endif

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID

tensil_error_t
tensil_compute_unit_init_sampling(struct tensil_compute_unit *tcu,
                                  size_t sample_block_size);

tensil_error_t
tensil_compute_unit_start_sampling(struct tensil_compute_unit *tcu,
                                   struct tensil_sample_buffer *buffer);

void tensil_compute_unit_complete_sampling(struct tensil_compute_unit *tcu,
                                           struct tensil_sample_buffer *buffer);

bool tensil_compute_unit_is_sample_busy(struct tensil_compute_unit *tcu);

#endif
