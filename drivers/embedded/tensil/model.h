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

#define MAX_CONSTS 1
#define MAX_INPUTS 4
#define MAX_OUTPUTS 4

struct program {
#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
    char file_name[MAX_STRING_SIZE];
#endif
    size_t size;
};

struct consts_entry {
#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
    char file_name[MAX_STRING_SIZE];
#endif
    size_t base;
    size_t size;
};

struct input_output_entry {
    char name[MAX_STRING_SIZE];
    size_t base;
    size_t size;
};

struct model {
    struct consts_entry consts[MAX_CONSTS];
    size_t consts_size;

    struct input_output_entry inputs[MAX_INPUTS];
    size_t inputs_size;

    struct input_output_entry outputs[MAX_OUTPUTS];
    size_t outputs_size;

    struct program prog;
    struct architecture arch;
};

bool model_is_valid(const struct model *model);

void model_parse(struct model *model, const cJSON *json);

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

error_t model_from_file(struct model *model, const char *file_name);

#endif
