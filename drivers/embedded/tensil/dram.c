/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#include "dram.h"

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "xil_cache.h"
#include "xstatus.h"

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
#include "ff.h"
#endif

#define FP16BP8_SIZE 2

static const float fp16bp8_ratio = (1 << 8);
static const float fp16bp8_max = (float)INT16_MAX / fp16bp8_ratio;
static const float fp16bp8_min = (float)INT16_MIN / fp16bp8_ratio;
static const float fp16bp8_error = 0.2;
typedef int16_t fp16bp8_bits;

static void read_fp16bp8(const uint8_t *bank_ptr, size_t offset, size_t size,
                         float *buffer) {
    const uint8_t *base_ptr = bank_ptr + offset * FP16BP8_SIZE;
    Xil_DCacheFlushRange((UINTPTR)base_ptr, size * FP16BP8_SIZE);

    for (size_t i = 0; i < size; i++) {
        buffer[i] =
            ((float)*((const fp16bp8_bits *)(base_ptr + i * FP16BP8_SIZE))) /
            fp16bp8_ratio;
    }
}

static void write_fp16bp8(uint8_t *bank_ptr, size_t offset, size_t size,
                          const float *buffer) {
    uint8_t *base_ptr = bank_ptr + offset * FP16BP8_SIZE;
    memset((void *)base_ptr, 0, size * FP16BP8_SIZE);

    for (size_t i = 0; i < size; i++) {
        *((fp16bp8_bits *)(base_ptr + i * FP16BP8_SIZE)) =
            (fp16bp8_bits)roundf(buffer[i] * fp16bp8_ratio);
    }

    Xil_DCacheFlushRange((UINTPTR)base_ptr, size * FP16BP8_SIZE);
}

size_t tensil_dram_sizeof_scalar(enum tensil_data_type type) {
    switch (type) {
    case TENSIL_DATA_TYPE_FP16BP8:
    default:
        return FP16BP8_SIZE;
    }
}

float tensil_dram_max_scalar(enum tensil_data_type type) {
    switch (type) {
    case TENSIL_DATA_TYPE_FP16BP8:
    default:
        return fp16bp8_max;
    }
}

float tensil_dram_min_scalar(enum tensil_data_type type) {
    switch (type) {
    case TENSIL_DATA_TYPE_FP16BP8:
    default:
        return fp16bp8_min;
    }
}

float tensil_dram_max_error_scalar(enum tensil_data_type type) {
    switch (type) {
    case TENSIL_DATA_TYPE_FP16BP8:
    default:
        return fp16bp8_error;
    }
}

void tensil_dram_read_scalars(const uint8_t *bank_ptr,
                              enum tensil_data_type type, size_t offset,
                              size_t size, float *buffer) {
    switch (type) {
    case TENSIL_DATA_TYPE_FP16BP8:
    default:
        read_fp16bp8(bank_ptr, offset, size, buffer);
        break;
    }
}

void tensil_dram_write_scalars(uint8_t *bank_ptr, enum tensil_data_type type,
                               size_t offset, size_t size,
                               const float *buffer) {
    switch (type) {
    case TENSIL_DATA_TYPE_FP16BP8:
    default:
        write_fp16bp8(bank_ptr, offset, size, buffer);
        break;
    }
}

void tensil_dram_fill_random(uint8_t *bank_ptr, enum tensil_data_type type,
                             size_t offset, size_t size) {
    uint8_t *base_ptr = bank_ptr + offset * tensil_dram_sizeof_scalar(type);
    size_t size_bytes = size * tensil_dram_sizeof_scalar(type);

    for (size_t i = 0; i < size_bytes; i++) {
        *(base_ptr + i) = rand() & 0xff;
    }

    Xil_DCacheFlushRange((UINTPTR)base_ptr, size_bytes);
}

void tensil_dram_fill_bytes(uint8_t *bank_ptr, enum tensil_data_type type,
                            size_t offset, int byte, size_t size) {
    uint8_t *base_ptr = bank_ptr + offset * tensil_dram_sizeof_scalar(type);
    size_t size_bytes = size * tensil_dram_sizeof_scalar(type);

    memset((void *)base_ptr, byte, size_bytes);

    Xil_DCacheFlushRange((UINTPTR)base_ptr, size_bytes);
}

int tensil_dram_compare_bytes(uint8_t *bank0_ptr, uint8_t *bank1_ptr,
                              enum tensil_data_type type, size_t offset0,
                              size_t offset1, size_t size) {
    uint8_t *base0_ptr = bank0_ptr + offset0 * tensil_dram_sizeof_scalar(type);
    uint8_t *base1_ptr = bank1_ptr + offset1 * tensil_dram_sizeof_scalar(type);
    size_t size_bytes = size * tensil_dram_sizeof_scalar(type);

    Xil_DCacheFlushRange((UINTPTR)base0_ptr, size_bytes);
    Xil_DCacheFlushRange((UINTPTR)base1_ptr, size_bytes);

    return memcmp((const void *)base0_ptr, (const void *)base1_ptr, size_bytes);
}

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

tensil_error_t tensil_dram_write_scalars_from_file(uint8_t *bank_ptr,
                                                   enum tensil_data_type type,
                                                   size_t offset, size_t size,
                                                   const char *file_name) {
    FIL fil;
    FILINFO fno;
    FRESULT res;
    UINT bytes_read;
    size_t sizeof_scalar = tensil_dram_sizeof_scalar(type);
    uint8_t *base_ptr = bank_ptr + offset * sizeof_scalar;

    memset(&fno, 0, sizeof(FILINFO));
    res = f_stat(file_name, &fno);
    if (res)
        return TENSIL_FS_ERROR(res);

    if (fno.fsize != size * sizeof_scalar)
        return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_UNEXPECTED_CONSTS_SIZE,
                                   "Unexpected consts size in %s", file_name);

    memset(&fil, 0, sizeof(FIL));
    res = f_open(&fil, file_name, FA_READ);
    if (res)
        return TENSIL_FS_ERROR(res);

    res = f_read(&fil, (void *)base_ptr, fno.fsize, &bytes_read);
    f_close(&fil);

    if (res)
        return TENSIL_FS_ERROR(res);

    Xil_DCacheFlushRange((UINTPTR)base_ptr, fno.fsize);

    return TENSIL_ERROR_NONE;
}

#endif
