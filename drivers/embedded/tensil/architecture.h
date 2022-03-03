/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "cJSON.h"
#include "error.h"
#include "platform.h"

enum data_type { TENSIL_DATA_TYPE_INVALID = 0, TENSIL_DATA_TYPE_FP16BP8 = 1 };

struct architecture {
    size_t array_size;
    enum data_type data_type;

    size_t local_depth;
    size_t accumulator_depth;
    size_t dram0_depth;
    size_t dram1_depth;
    size_t stride0_depth;
    size_t stride1_depth;
    size_t simd_registers_depth;

    size_t sample_block_size;
    uint16_t decoder_timeout;
};

bool architecture_is_valid(const struct architecture *arch);

bool architecture_is_compatible(const struct architecture *driver_arch,
                                const struct architecture *model_arch);

void architecture_parse(struct architecture *arch, const cJSON *json);
