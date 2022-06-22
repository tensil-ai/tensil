/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#include "error.h"

#ifdef TENSIL_PLATFORM_ENABLE_STDIO
#include <stdarg.h>
#include <stdio.h>
#endif

struct tensil_error tensil_last_error;

tensil_error_t tensil_error_set_driver(struct tensil_error *error,
                                       enum tensil_error_code code,
                                       const char *format, ...) {
    error->type = TENSIL_ERROR_DRIVER;
    error->code.code = code;

#ifdef TENSIL_PLATFORM_ENABLE_STDIO
    va_list l;

    va_start(l, format);
    vsnprintf(error->message, TENSIL_ERROR_MAX_MESSAGE_SIZE, format, l);
    va_end(l);
#endif

    return error;
}

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

tensil_error_t tensil_error_set_fs(struct tensil_error *error, FRESULT fresult,
                                   const char *format, ...) {
    error->type = TENSIL_ERROR_FS;
    error->code.fresult = fresult;

#ifdef TENSIL_PLATFORM_ENABLE_STDIO
    va_list l;

    va_start(l, format);
    vsnprintf(error->message, TENSIL_ERROR_MAX_MESSAGE_SIZE, format, l);
    va_end(l);
#endif

    return error;
}

#endif

tensil_error_t tensil_error_set_xilinx(struct tensil_error *error, int xstatus,
                                       const char *format, ...) {
    error->type = TENSIL_ERROR_XILINX;
    error->code.xstatus = xstatus;

#ifdef TENSIL_PLATFORM_ENABLE_STDIO
    va_list l;

    va_start(l, format);
    vsnprintf(error->message, TENSIL_ERROR_MAX_MESSAGE_SIZE, format, l);
    va_end(l);
#endif

    return error;
}

#ifdef TENSIL_PLATFORM_ENABLE_STDIO
void tensil_error_print(tensil_error_t error) {
    printf("Error: %s\n", error->message);
}
#endif