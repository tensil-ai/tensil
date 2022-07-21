/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include "platform.h"

#include <stdbool.h>
#include <stddef.h>

#include "architecture.h"
#include "cJSON.h"
#include "config.h"
#include "error.h"

#define TENSIL_MAX_CONSTS 1
#define TENSIL_MAX_INPUTS 4
#define TENSIL_MAX_OUTPUTS 4

struct tensil_program {
#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
    char file_name[TENSIL_MAX_STRING_SIZE];
#endif
    size_t size;
};

struct tensil_consts_entry {
#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
    char file_name[TENSIL_MAX_STRING_SIZE];
#endif
    size_t base;
    size_t size;
};

struct tensil_input_output_entry {
    char name[TENSIL_MAX_STRING_SIZE];
    size_t base;
    size_t size;
};

struct tensil_model {
    struct tensil_consts_entry consts[TENSIL_MAX_CONSTS];
    size_t consts_size;

    struct tensil_input_output_entry inputs[TENSIL_MAX_INPUTS];
    size_t inputs_size;

    struct tensil_input_output_entry outputs[TENSIL_MAX_OUTPUTS];
    size_t outputs_size;

    struct tensil_program prog;
    struct tensil_architecture arch;

    bool load_consts_to_local;
};

bool tensil_model_is_valid(const struct tensil_model *model);

#ifdef TENSIL_PLATFORM_ENABLE_STDIO

void tensil_model_parse(struct tensil_model *model, const cJSON *json);

#endif

#if (defined(TENSIL_PLATFORM_ENABLE_FILE_SYSTEM) &&                            \
     defined(TENSIL_PLATFORM_ENABLE_STDIO))

tensil_error_t tensil_model_from_file(struct tensil_model *model,
                                      const char *file_name);

#endif
