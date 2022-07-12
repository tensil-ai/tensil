/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include <stddef.h>
#include <stdint.h>

#include "architecture.h"
#include "error.h"

size_t tensil_dram_sizeof_scalar(enum tensil_data_type type);

float tensil_dram_max_scalar(enum tensil_data_type type);

float tensil_dram_min_scalar(enum tensil_data_type type);

float tensil_dram_max_error_scalar(enum tensil_data_type type);

void tensil_dram_read_scalars(const uint8_t *bank_ptr,
                              enum tensil_data_type type, size_t offset,
                              size_t size, float *buffer);

void tensil_dram_write_scalars(uint8_t *bank_ptr, enum tensil_data_type type,
                               size_t offset, size_t size, const float *buffer);

void tensil_dram_fill_random(uint8_t *bank_ptr, enum tensil_data_type type,
                             size_t offset, size_t size);

void tensil_dram_fill_bytes(uint8_t *bank_ptr, enum tensil_data_type type,
                            size_t offset, int byte, size_t size);

int tensil_dram_compare_bytes(uint8_t *bank0_ptr, uint8_t *bank1_ptr,
                              enum tensil_data_type type, size_t offset0,
                              size_t offset1, size_t size);

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

tensil_error_t tensil_dram_write_scalars_from_file(uint8_t *bank_ptr,
                                                   enum tensil_data_type type,
                                                   size_t offset, size_t size,
                                                   const char *file_name);

#endif
