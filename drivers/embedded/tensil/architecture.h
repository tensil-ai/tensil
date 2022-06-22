/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "cJSON.h"
#include "error.h"
#include "platform.h"

enum tensil_data_type {
    TENSIL_DATA_TYPE_INVALID = 0,
    TENSIL_DATA_TYPE_FP16BP8 = 1
};

struct tensil_architecture {
    size_t array_size;
    enum tensil_data_type data_type;

    size_t local_depth;
    size_t accumulator_depth;
    size_t dram0_depth;
    size_t dram1_depth;
    size_t stride0_depth;
    size_t stride1_depth;
    size_t simd_registers_depth;
};

bool tensil_architecture_is_valid(const struct tensil_architecture *arch);

bool tensil_architecture_is_compatible(
    const struct tensil_architecture *driver_arch,
    const struct tensil_architecture *model_arch);

#ifdef TENSIL_PLATFORM_ENABLE_STDIO

void tensil_architecture_parse(struct tensil_architecture *arch,
                               const cJSON *json);

#endif
