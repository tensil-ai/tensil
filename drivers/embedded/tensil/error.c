/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#include "error.h"

#include <stdarg.h>
#include <stdio.h>

struct error last_error;

error_t error_set_driver(struct error *error, enum error_code code,
                         const char *format, ...) {
    va_list l;

    error->type = ERROR_DRIVER;
    error->code.code = code;

    va_start(l, format);
    vsnprintf(error->message, ERROR_MAX_MESSAGE_SIZE, format, l);
    va_end(l);

    return error;
}

#ifdef TENSIL_PLATFORM_ENABLE_FATFS

error_t error_set_fs(struct error *error, FRESULT fresult, const char *format,
                     ...) {
    va_list l;

    error->type = ERROR_FS;
    error->code.fresult = fresult;

    va_start(l, format);
    vsnprintf(error->message, ERROR_MAX_MESSAGE_SIZE, format, l);
    va_end(l);

    return error;
}

#endif

error_t error_set_xilinx(struct error *error, int xstatus, const char *format,
                         ...) {
    va_list l;

    error->type = ERROR_XILINX;
    error->code.xstatus = xstatus;

    va_start(l, format);
    vsnprintf(error->message, ERROR_MAX_MESSAGE_SIZE, format, l);
    va_end(l);

    return error;
}

void error_print(error_t error) { printf("Error: %s\n", error->message); }