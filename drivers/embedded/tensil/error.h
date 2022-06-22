/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include "platform.h"
#include "xstatus.h"

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
#include "ff.h"
#endif

#define TENSIL_ERROR_MAX_MESSAGE_SIZE 256
#define TENSIL_ERROR_NONE NULL

enum tensil_error_type {
    TENSIL_ERROR_DRIVER,
    TENSIL_ERROR_FS,
    TENSIL_ERROR_XILINX
};

enum tensil_error_code {
    TENSIL_ERROR_DRIVER_NONE = 0,
    TENSIL_ERROR_DRIVER_AXI_DMA_DEVICE_NOT_FOUND,
    TENSIL_ERROR_DRIVER_INSUFFICIENT_BUFFER,
    TENSIL_ERROR_DRIVER_UNEXPECTED_CONSTS_SIZE,
    TENSIL_ERROR_DRIVER_UNEXPECTED_PROGRAM_SIZE,
    TENSIL_ERROR_DRIVER_INVALID_JSON,
    TENSIL_ERROR_DRIVER_INVALID_MODEL,
    TENSIL_ERROR_DRIVER_INVALID_ARCH,
    TENSIL_ERROR_DRIVER_INVALID_PLATFORM,
    TENSIL_ERROR_DRIVER_INCOMPATIBLE_MODEL,
    TENSIL_ERROR_DRIVER_UNEXPECTED_INPUT_NAME,
    TENSIL_ERROR_DRIVER_UNEXPECTED_OUTPUT_NAME,
    TENSIL_ERROR_DRIVER_OUT_OF_HEAP_MEMORY,
    TENSIL_ERROR_DRIVER_OUT_OF_SAMPLE_BUFFER
};

struct tensil_error {
#ifdef TENSIL_PLATFORM_ENABLE_STDIO
    char message[TENSIL_ERROR_MAX_MESSAGE_SIZE];
#endif
    enum tensil_error_type type;

    union {
        enum tensil_error_code code;
#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
        FRESULT fresult;
#endif
        int xstatus;
    } code;
};

typedef const struct tensil_error *tensil_error_t;

extern struct tensil_error tensil_last_error;

#define TENSIL_DRIVER_ERROR(code, ...)                                         \
    tensil_error_set_driver(&tensil_last_error, code, ##__VA_ARGS__)

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
#define TENSIL_FS_ERROR(fresult)                                               \
    tensil_error_set_fs(&tensil_last_error, fresult,                           \
                        "%s:%d file system result %d", __FILE__, __LINE__,     \
                        fresult)

#define TENSIL_FS_RESULT_FRAME FRESULT fresult = FR_OK;

#define TENSIL_FS_RESULT(call)                                                 \
    ((fresult = call) == FR_OK) ? TENSIL_ERROR_NONE : TENSIL_FS_ERROR(fresult)

#endif

#define TENSIL_XILINX_ERROR(xstatus)                                           \
    tensil_error_set_xilinx(&tensil_last_error, xstatus,                       \
                            "%s:%d Xilinx status %d", __FILE__, __LINE__,      \
                            xstatus)

#define TENSIL_XILINX_RESULT_FRAME int xilinx_status = XST_SUCCESS;

#define TENSIL_XILINX_RESULT(call)                                             \
    ((xilinx_status = call) == XST_SUCCESS)                                    \
        ? TENSIL_ERROR_NONE                                                    \
        : TENSIL_XILINX_ERROR(xilinx_status)

tensil_error_t tensil_error_set_driver(struct tensil_error *error,
                                       enum tensil_error_code code,
                                       const char *format, ...);

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
tensil_error_t tensil_error_set_fs(struct tensil_error *error, FRESULT fresult,
                                   const char *format, ...);
#endif

tensil_error_t tensil_error_set_xilinx(struct tensil_error *error, int xstatus,
                                       const char *format, ...);

#ifdef TENSIL_PLATFORM_ENABLE_STDIO
void tensil_error_print(tensil_error_t error);
#endif