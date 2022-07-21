/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#include "model.h"

#include "config.h"
#include <malloc.h>
#include <string.h>

#ifdef TENSIL_PLATFORM_ENABLE_STDIO
#include "cJSON.h"
#include <stdio.h>
#endif

#define JSMN_TOKEN_POOL_SIZE 256

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
#include "ff.h"
#endif

static bool
is_input_output_entry_valid(const struct tensil_input_output_entry *entry) {
    return (strlen(entry->name) > 0 && entry->size > 0);
}

static bool is_consts_entry_valid(const struct tensil_consts_entry *entry) {
    return (
#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
        strlen(entry->file_name) > 0 &&
#endif
        entry->size > 0);
}

bool tensil_model_is_valid(const struct tensil_model *model) {
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
        tensil_architecture_is_valid(&model->arch));
}

#ifdef TENSIL_PLATFORM_ENABLE_STDIO

static void parse_prog(struct tensil_program *program, const cJSON *json) {
    memset(program, 0, sizeof(struct tensil_program));

    if (cJSON_IsObject(json)) {
#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
        tensil_config_parse_object_item_as_string(json, "file_name",
                                                  program->file_name);
#endif
        tensil_config_parse_object_item_as_size(json, "size", &program->size);
    }
}

static void parse_consts_entry(struct tensil_consts_entry *entry,
                               const cJSON *json) {
    memset(entry, 0, sizeof(struct tensil_consts_entry));

    if (cJSON_IsObject(json)) {
#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
        tensil_config_parse_object_item_as_string(json, "file_name",
                                                  entry->file_name);
#endif
        tensil_config_parse_object_item_as_size(json, "base", &entry->base);
        tensil_config_parse_object_item_as_size(json, "size", &entry->size);
    }
}

static void parse_consts(struct tensil_model *model, const cJSON *json) {
    if (cJSON_IsArray(json) && cJSON_GetArraySize(json) <= TENSIL_MAX_CONSTS) {
        model->consts_size = cJSON_GetArraySize(json);
        for (size_t i = 0; i < TENSIL_MAX_CONSTS; i++)
            parse_consts_entry(&model->consts[i], cJSON_GetArrayItem(json, i));
    }
}

static void parse_input_output_entry(struct tensil_input_output_entry *entry,
                                     const cJSON *json) {
    memset(entry, 0, sizeof(struct tensil_input_output_entry));

    if (cJSON_IsObject(json)) {
        tensil_config_parse_object_item_as_string(json, "name", entry->name);
        tensil_config_parse_object_item_as_size(json, "base", &entry->base);
        tensil_config_parse_object_item_as_size(json, "size", &entry->size);
    }
}

static void parse_inputs(struct tensil_model *model, const cJSON *json) {

    if (cJSON_IsArray(json) && cJSON_GetArraySize(json) <= TENSIL_MAX_INPUTS) {
        model->inputs_size = cJSON_GetArraySize(json);
        for (size_t i = 0; i < TENSIL_MAX_INPUTS; i++)
            parse_input_output_entry(&model->inputs[i],
                                     cJSON_GetArrayItem(json, i));
    }
}

static void parse_outputs(struct tensil_model *model, const cJSON *json) {
    if (cJSON_IsArray(json) && cJSON_GetArraySize(json) <= TENSIL_MAX_OUTPUTS) {
        model->outputs_size = cJSON_GetArraySize(json);
        for (size_t i = 0; i < TENSIL_MAX_OUTPUTS; i++)
            parse_input_output_entry(&model->outputs[i],
                                     cJSON_GetArrayItem(json, i));
    }
}

void tensil_model_parse(struct tensil_model *model, const cJSON *json) {
    memset((void *)model, 0, sizeof(struct tensil_model));

    if (cJSON_IsObject(json)) {
        parse_prog(&model->prog,
                   cJSON_GetObjectItemCaseSensitive(json, "prog"));
        parse_consts(model, cJSON_GetObjectItemCaseSensitive(json, "consts"));
        parse_inputs(model, cJSON_GetObjectItemCaseSensitive(json, "inputs"));
        parse_outputs(model, cJSON_GetObjectItemCaseSensitive(json, "outputs"));

        tensil_architecture_parse(
            &model->arch, cJSON_GetObjectItemCaseSensitive(json, "arch"));

        tensil_config_parse_object_item_as_bool(json, "load_consts_to_local",
                                                &model->load_consts_to_local);
    }
}

#endif

#if (defined(TENSIL_PLATFORM_ENABLE_FILE_SYSTEM) &&                            \
     defined(TENSIL_PLATFORM_ENABLE_STDIO))

tensil_error_t tensil_model_from_file(struct tensil_model *model,
                                      const char *file_name) {
    FIL fil;
    FILINFO fno;
    FRESULT res;
    UINT bytes_read;
    tensil_error_t error = TENSIL_ERROR_NONE;
    char *buffer = NULL;
    cJSON *json = NULL;

    memset(&fno, 0, sizeof(FILINFO));
    res = f_stat(file_name, &fno);
    if (res)
        return TENSIL_FS_ERROR(res);

    buffer = (char *)malloc(fno.fsize);
    if (!buffer)
        return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_OUT_OF_HEAP_MEMORY,
                                   "Out of heap memory");

    memset(&fil, 0, sizeof(FIL));
    res = f_open(&fil, file_name, FA_READ);
    if (res) {
        error = TENSIL_FS_ERROR(res);
        goto cleanup;
    }

    res = f_read(&fil, (void *)buffer, fno.fsize, &bytes_read);
    f_close(&fil);
    if (res) {
        error = TENSIL_FS_ERROR(res);
        goto cleanup;
    }

    json = cJSON_ParseWithLength(buffer, fno.fsize);

    if (json) {
        tensil_model_parse(model, json);
    } else {
        error = TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_INVALID_JSON,
                                    "Invalid JSON in %s", file_name);
        goto cleanup;
    }

    if (!tensil_model_is_valid(model)) {
        error = TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_INVALID_MODEL,
                                    "Invalid model in %s", file_name);
        goto cleanup;
    }

    const char *file_name_ptr = file_name;
    const char *file_name_slash_ptr = NULL;
    size_t i = 0;
    while ((file_name_slash_ptr = strchr(file_name_ptr, '/'))) {
        while (file_name_ptr <= file_name_slash_ptr) {
            model->path[i] = *file_name_ptr;
            i++;
            file_name_ptr++;
        }
    }

cleanup:
    cJSON_Delete(json);
    free(buffer);

    return error;
}

#endif
