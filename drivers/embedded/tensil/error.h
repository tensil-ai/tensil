/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include "platform.h"

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
#include "ff.h"
#endif

#define ERROR_MAX_MESSAGE_SIZE 256
#define ERROR_NONE NULL

enum error_type { ERROR_DRIVER, ERROR_FS, ERROR_XILINX };

enum error_code {
    ERROR_DRIVER_NONE = 0,
    ERROR_DRIVER_AXI_DMA_DEVICE_NOT_FOUND,
    ERROR_DRIVER_INSUFFICIENT_BUFFER,
    ERROR_DRIVER_UNEXPECTED_CONSTS_SIZE,
    ERROR_DRIVER_UNEXPECTED_PROGRAM_SIZE,
    ERROR_DRIVER_INVALID_JSON,
    ERROR_DRIVER_INVALID_MODEL,
    ERROR_DRIVER_INVALID_ARCH,
    ERROR_DRIVER_INVALID_PLATFORM,
    ERROR_DRIVER_INCOMPATIBLE_MODEL,
    ERROR_DRIVER_UNEXPECTED_INPUT_NAME,
    ERROR_DRIVER_UNEXPECTED_OUTPUT_NAME,
    ERROR_DRIVER_OUT_OF_HEAP_MEMORY,
    ERROR_DRIVER_OUT_OF_SAMPLE_BUFFER
};

struct error {
    char message[ERROR_MAX_MESSAGE_SIZE];
    enum error_type type;

    union {
        enum error_code code;
#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
        FRESULT fresult;
#endif
        int xstatus;
    } code;
};

typedef const struct error *error_t;

extern struct error last_error;

#define DRIVER_ERROR(code, ...)                                                \
    error_set_driver(&last_error, code, ##__VA_ARGS__)

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
#define FS_ERROR(fresult)                                                      \
    error_set_fs(&last_error, fresult, "%s:%d file system result %d",          \
                 __FILE__, __LINE__, fresult)
#endif

#define XILINX_ERROR(xstatus)                                                  \
    error_set_xilinx(&last_error, xstatus, "%s:%d Xilinx status %d", __FILE__, \
                     __LINE__, xstatus)

error_t error_set_driver(struct error *error, enum error_code code,
                         const char *format, ...);

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
error_t error_set_fs(struct error *error, FRESULT fresult, const char *format,
                     ...);
#endif

error_t error_set_xilinx(struct error *error, int xstatus, const char *format,
                         ...);

void error_print(error_t error);