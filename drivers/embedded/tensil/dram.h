/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include <stddef.h>
#include <stdint.h>

#include "architecture.h"
#include "error.h"

size_t dram_sizeof_scalar(enum data_type type);

float dram_max_scalar(enum data_type type);

float dram_min_scalar(enum data_type type);

float dram_max_error_scalar(enum data_type type);

void dram_read_scalars(const uint8_t *bank_ptr, enum data_type type,
                       size_t offset, size_t size, float *buffer);

void dram_write_scalars(uint8_t *bank_ptr, enum data_type type, size_t offset,
                        size_t size, const float *buffer);

void dram_write_random_scalars(uint8_t *bank_ptr, enum data_type type,
                               size_t offset, size_t size);

void dram_fill_scalars(uint8_t *bank_ptr, enum data_type type, size_t offset,
                       int byte, size_t size);

int dram_compare_scalars(uint8_t *bank_ptr, enum data_type type, size_t offset0,
                         size_t offset1, size_t size);

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

error_t dram_write_scalars_from_file(uint8_t *bank_ptr, enum data_type type,
                                     size_t offset, size_t size,
                                     const char *file_name);

#endif