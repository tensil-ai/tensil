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

struct tcu {
#ifdef TENSIL_PLATFORM_INSTRUCTION_AXI_DMA_DEVICE_ID
    XAxiDma instruction_axi_dma;
#endif
#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    XAxiDma sample_axi_dma;
    size_t sample_block_size;
#endif
};

struct sample_buffer;
struct instruction_buffer;

error_t tcu_init(struct tcu *tcu, size_t sample_block_size);

#ifdef TENSIL_PLATFORM_INSTRUCTION_AXI_DMA_DEVICE_ID

error_t tcu_start_instructions(struct tcu *tcu,
                               const struct instruction_buffer *buffer,
                               size_t *run_offset);

bool tcu_is_instructions_busy(struct tcu *tcu);

int tcu_get_instructions_data_width_bytes(struct tcu *tcu);

#endif

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID

error_t tcu_start_sampling(struct tcu *tcu, struct sample_buffer *buffer);

void tcu_complete_sampling(struct tcu *tcu, struct sample_buffer *buffer);

bool tcu_is_sample_busy(struct tcu *tcu);

#endif