/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include <stdint.h>

#include "cJSON.h"

#define TENSIL_MAX_STRING_SIZE 256

void tensil_config_parse_object_item_as_size(const cJSON *json,
                                             const char *name, size_t *target);

void tensil_config_parse_object_item_as_uint16(const cJSON *json,
                                               const char *name,
                                               uint16_t *target);

void tensil_config_parse_object_item_as_string(const cJSON *json,
                                               const char *name, char *target);