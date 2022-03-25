/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#include "model.h"

#include "cJSON.h"
#include "config.h"
#include <malloc.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#define JSMN_TOKEN_POOL_SIZE 256

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
#include "ff.h"
#endif

static bool
is_input_output_entry_valid(const struct input_output_entry *entry) {
    return (strlen(entry->name) > 0 && entry->size > 0);
}

static bool is_consts_entry_valid(const struct consts_entry *entry) {
    return (
#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
        strlen(entry->file_name) > 0 &&
#endif
        entry->size > 0);
}

bool model_is_valid(const struct model *model) {
    bool consts_valid = true;
    for (size_t i = 0; i < model->consts_size; i++) {
        consts_valid &= is_consts_entry_valid(&model->consts[i]);
    }

    bool inputs_valid = true;
    for (size_t i = 0; i < model->inputs_size; i++) {
        inputs_valid &= is_input_output_entry_valid(&model->inputs[i]);
    }

    bool outputs_valid = true;
    for (size_t i = 0; i < model->outputs_size; i++) {
        outputs_valid &= is_input_output_entry_valid(&model->outputs[i]);
    }

    return (
#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
        strlen(model->prog.file_name) > 0 &&
#endif
        model->consts_size > 0 && consts_valid && model->inputs_size > 0 &&
        inputs_valid && model->outputs_size > 0 && outputs_valid &&
        architecture_is_valid(&model->arch));
}

static void parse_prog(struct program *program, const cJSON *json) {
    memset(program, 0, sizeof(struct program));

    if (cJSON_IsObject(json)) {
#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
        config_parse_object_item_as_string(json, "file_name",
                                           program->file_name);
#endif
        config_parse_object_item_as_size(json, "size", &program->size);
    }
}

static void parse_consts_entry(struct consts_entry *entry, const cJSON *json) {
    memset(entry, 0, sizeof(struct consts_entry));

    if (cJSON_IsObject(json)) {
#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
        config_parse_object_item_as_string(json, "file_name", entry->file_name);
#endif
        config_parse_object_item_as_size(json, "base", &entry->base);
        config_parse_object_item_as_size(json, "size", &entry->size);
    }
}

static void parse_consts(struct model *model, const cJSON *json) {
    if (cJSON_IsArray(json) && cJSON_GetArraySize(json) <= MAX_CONSTS) {
        model->consts_size = cJSON_GetArraySize(json);
        for (size_t i = 0; i < MAX_CONSTS; i++)
            parse_consts_entry(&model->consts[i], cJSON_GetArrayItem(json, i));
    }
}

static void parse_input_output_entry(struct input_output_entry *entry,
                                     const cJSON *json) {
    memset(entry, 0, sizeof(struct input_output_entry));

    if (cJSON_IsObject(json)) {
        config_parse_object_item_as_string(json, "name", entry->name);
        config_parse_object_item_as_size(json, "base", &entry->base);
        config_parse_object_item_as_size(json, "size", &entry->size);
    }
}

static void parse_inputs(struct model *model, const cJSON *json) {

    if (cJSON_IsArray(json) && cJSON_GetArraySize(json) <= MAX_INPUTS) {
        model->inputs_size = cJSON_GetArraySize(json);
        for (size_t i = 0; i < MAX_INPUTS; i++)
            parse_input_output_entry(&model->inputs[i],
                                     cJSON_GetArrayItem(json, i));
    }
}

static void parse_outputs(struct model *model, const cJSON *json) {
    if (cJSON_IsArray(json) && cJSON_GetArraySize(json) <= MAX_OUTPUTS) {
        model->outputs_size = cJSON_GetArraySize(json);
        for (size_t i = 0; i < MAX_OUTPUTS; i++)
            parse_input_output_entry(&model->outputs[i],
                                     cJSON_GetArrayItem(json, i));
    }
}

void model_parse(struct model *model, const cJSON *json) {
    memset(model, 0, sizeof(struct model));

    if (cJSON_IsObject(json)) {
        parse_prog(&model->prog,
                   cJSON_GetObjectItemCaseSensitive(json, "prog"));
        parse_consts(model, cJSON_GetObjectItemCaseSensitive(json, "consts"));
        parse_inputs(model, cJSON_GetObjectItemCaseSensitive(json, "inputs"));
        parse_outputs(model, cJSON_GetObjectItemCaseSensitive(json, "outputs"));

        architecture_parse(&model->arch,
                           cJSON_GetObjectItemCaseSensitive(json, "arch"));
    }
}

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

error_t model_from_file(struct model *model, const char *file_name) {
    FIL fil;
    FILINFO fno;
    FRESULT res;
    UINT bytes_read;
    error_t error = ERROR_NONE;
    char *buffer = NULL;
    cJSON *json = NULL;

    memset((void *)model, 0, sizeof(struct model));

    memset(&fno, 0, sizeof(FILINFO));
    res = f_stat(file_name, &fno);
    if (res)
        return FS_ERROR(res);

    buffer = (char *)malloc(fno.fsize);
    if (!buffer)
        return DRIVER_ERROR(ERROR_DRIVER_OUT_OF_HEAP_MEMORY,
                            "Out of heap memory");

    memset(&fil, 0, sizeof(FIL));
    res = f_open(&fil, file_name, FA_READ);
    if (res) {
        error = FS_ERROR(res);
        goto cleanup;
    }

    res = f_read(&fil, (void *)buffer, fno.fsize, &bytes_read);
    f_close(&fil);
    if (res) {
        error = FS_ERROR(res);
        goto cleanup;
    }

    json = cJSON_ParseWithLength(buffer, fno.fsize);

    if (json) {
        model_parse(model, json);
    } else {
        error = DRIVER_ERROR(ERROR_DRIVER_INVALID_JSON, "Invalid JSON in %s",
                             file_name);
        goto cleanup;
    }

    if (!model_is_valid(model)) {
        error = DRIVER_ERROR(ERROR_DRIVER_INVALID_MODEL, "Invalid model in %s",
                             file_name);
        goto cleanup;
    }

cleanup:
    cJSON_Delete(json);
    free(buffer);

    return error;
}

#endif
