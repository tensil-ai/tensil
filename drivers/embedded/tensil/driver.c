/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#include "driver.h"

#include <malloc.h>
#include <math.h>
#include <string.h>

#ifdef TENSIL_PLATFORM_ENABLE_STDIO
#include <stdio.h>
#endif

#include "dram.h"
#include "instruction_buffer.h"
#include "model.h"
#include "sample_buffer.h"
#include "tcu.h"

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
#include "ff.h"
#endif

#include "../architecture_params.h"

#define PROGRAM_COUNTER_SHIFT 1

uint8_t *
tensil_driver_get_dram_bank_base_ptr(const struct tensil_driver *driver,
                                     enum tensil_dram_bank dram_bank) {
    switch (dram_bank) {
    case TENSIL_DRAM0:
        return driver->dram0_base_ptr;
    case TENSIL_DRAM1:
    default:
        return driver->dram1_base_ptr;
    }
}

static size_t get_dram_bank_size(const struct tensil_driver *driver,
                                 enum tensil_dram_bank dram_bank) {
    switch (dram_bank) {
    case TENSIL_DRAM0:
        return driver->dram0_size;
    case TENSIL_DRAM1:
    default:
        return driver->dram1_size;
    }
}

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID

static tensil_error_t run_buffer_with_sampling(struct tensil_driver *driver) {
#ifdef TENSIL_PLATFORM_INSTRUCTION_AXI_DMA_DEVICE_ID

    tensil_error_t error = TENSIL_ERROR_NONE;
    size_t instructions_run_offset = 0;
    tensil_sample_buffer_reset(&driver->sample_buffer);

    bool instructions_busy = false;
    bool sample_busy = false;

    while (instructions_run_offset != driver->buffer.offset) {
        if (!instructions_busy) {
            error = tensil_compute_unit_start_instructions(
                &driver->tcu, &driver->buffer, &instructions_run_offset);

            if (error)
                return error;
        }

        if (!sample_busy) {
            error = tensil_compute_unit_start_sampling(&driver->tcu,
                                                       &driver->sample_buffer);

            if (error)
                return error;
        }

        do {
            sample_busy = tensil_compute_unit_is_sample_busy(&driver->tcu);
            instructions_busy =
                tensil_compute_unit_is_instructions_busy(&driver->tcu);
        } while (sample_busy && instructions_busy);

        if (!sample_busy)
            tensil_compute_unit_complete_sampling(&driver->tcu,
                                                  &driver->sample_buffer);
    }

    while (tensil_compute_unit_is_instructions_busy(&driver->tcu)) {
        if (!sample_busy) {
            error = tensil_compute_unit_start_sampling(&driver->tcu,
                                                       &driver->sample_buffer);

            if (error)
                return error;
        }

        sample_busy = tensil_compute_unit_is_sample_busy(&driver->tcu);

        if (!sample_busy)
            tensil_compute_unit_complete_sampling(&driver->tcu,
                                                  &driver->sample_buffer);
    }

    if (sample_busy) {
        while (tensil_compute_unit_is_sample_busy(&driver->tcu))
            ;

        tensil_compute_unit_complete_sampling(&driver->tcu,
                                              &driver->sample_buffer);
    }

    return TENSIL_ERROR_NONE;
#else
    return TENSIL_DRIVER_ERROR(
        TENSIL_ERROR_DRIVER_INVALID_PLATFORM,
        "Target must specify instruction AXI DMA device, see platform.h");
#endif
}

#else

static tensil_error_t
run_buffer(struct tensil_compute_unit *tcu,
           const struct tensil_instruction_buffer *buffer) {
#ifdef TENSIL_PLATFORM_INSTRUCTION_AXI_DMA_DEVICE_ID

    tensil_error_t error = TENSIL_ERROR_NONE;
    size_t instructions_run_offset = 0;

    while (instructions_run_offset != buffer->offset) {
        error = tensil_compute_unit_start_instructions(
            tcu, buffer, &instructions_run_offset);

        if (error)
            return error;

        while (tensil_compute_unit_is_instructions_busy(tcu))
            ;
    }

    return TENSIL_ERROR_NONE;
#else
    return TENSIL_DRIVER_ERROR(
        TENSIL_ERROR_DRIVER_INVALID_PLATFORM,
        "Target must specify instruction AXI DMA device, see platform.h");
#endif
}

#endif

static void fill_dram_vectors_with_bytes(struct tensil_driver *driver,
                                         enum tensil_dram_bank dram_bank,
                                         size_t offset, int byte, size_t size) {
    uint8_t *bank_ptr = tensil_driver_get_dram_bank_base_ptr(driver, dram_bank);

    tensil_dram_fill_bytes(bank_ptr, driver->arch.data_type,
                           offset * driver->arch.array_size, byte,
                           size * driver->arch.array_size);
}

static int compare_dram_vectors_bytes(struct tensil_driver *driver,
                                      enum tensil_dram_bank dram_bank0,
                                      enum tensil_dram_bank dram_bank1,
                                      size_t offset0, size_t offset1,
                                      size_t size) {
    uint8_t *bank0_ptr =
        tensil_driver_get_dram_bank_base_ptr(driver, dram_bank0);
    uint8_t *bank1_ptr =
        tensil_driver_get_dram_bank_base_ptr(driver, dram_bank1);

    return tensil_dram_compare_bytes(
        bank0_ptr, bank1_ptr, driver->arch.data_type,
        offset0 * driver->arch.array_size, offset1 * driver->arch.array_size,
        size * driver->arch.array_size);
}

static tensil_error_t append_flush_instructions(struct tensil_driver *driver) {
    size_t probe_source_offset = driver->arch.dram0_depth - 1;
    size_t probe_target_offset = driver->arch.dram0_depth - 2;
    size_t local_offset = driver->arch.local_depth - 1;

    tensil_error_t error = tensil_buffer_append_instruction(
        &driver->buffer, &driver->layout, TENSIL_OPCODE_DATA_MOVE,
        TENSIL_DATA_MOVE_FLAG_DRAM0_TO_LOCAL, local_offset, probe_source_offset,
        0);

    if (error)
        return error;

    error = tensil_buffer_append_instruction(
        &driver->buffer, &driver->layout, TENSIL_OPCODE_DATA_MOVE,
        TENSIL_DATA_MOVE_FLAG_LOCAL_TO_DRAM0, local_offset, probe_target_offset,
        0);

    return error;
}

static void reset_flush_probe(struct tensil_driver *driver) {
    size_t probe_source_offset = driver->arch.dram0_depth - 1;
    size_t probe_target_offset = driver->arch.dram0_depth - 2;

    fill_dram_vectors_with_bytes(driver, TENSIL_DRAM0, probe_source_offset, 0,
                                 1);
    fill_dram_vectors_with_bytes(driver, TENSIL_DRAM0, probe_target_offset,
                                 0xff, 1);
}

static void wait_for_flush(struct tensil_driver *driver) {
    size_t probe_source_offset = driver->arch.dram0_depth - 1;
    size_t probe_target_offset = driver->arch.dram0_depth - 2;

    while (compare_dram_vectors_bytes(driver, TENSIL_DRAM0, TENSIL_DRAM0,
                                      probe_source_offset, probe_target_offset,
                                      1) != 0)
        ;
}

tensil_error_t tensil_driver_run(struct tensil_driver *driver,
                                 const struct tensil_run_opts *run_opts) {
    reset_flush_probe(driver);

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    tensil_error_t error = run_buffer_with_sampling(driver);
#else
    tensil_error_t error = run_buffer(&driver->tcu, &driver->buffer);
#endif

    if (error)
        return error;

    wait_for_flush(driver);

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID

#ifdef TENSIL_PLATFORM_ENABLE_STDIO
    if (run_opts && (run_opts->print_sampling_summary ||
                     run_opts->print_sampling_aggregates ||
                     run_opts->print_sampling_listing)) {
        tensil_error_t error = tensil_sample_buffer_print_analysis(
            &driver->sample_buffer, &driver->buffer, &driver->layout,
            run_opts->print_sampling_summary,
            run_opts->print_sampling_aggregates,
            run_opts->print_sampling_listing, PROGRAM_COUNTER_SHIFT);

        if (error)
            return error;
    }
#endif

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
    if (run_opts && run_opts->sample_file_name) {
        tensil_error_t error = tensil_sample_buffer_to_file(
            &driver->sample_buffer, &driver->buffer, &driver->layout,
            run_opts->sample_file_name);

        if (error)
            return error;
    }
#endif

#endif
    return TENSIL_ERROR_NONE;
}

tensil_error_t
tensil_driver_setup_buffer_postamble(struct tensil_driver *driver) {
    tensil_error_t error = append_flush_instructions(driver);

    if (error)
        return error;

#ifdef TENSIL_PLATFORM_INSTRUCTION_AXI_DMA_DEVICE_ID
    error = tensil_buffer_pad_to_alignment(
        &driver->buffer, &driver->layout,
        tensil_compute_unit_get_instructions_data_width_bytes(&driver->tcu));

    if (error)
        return error;
#endif

    return TENSIL_ERROR_NONE;
}

tensil_error_t
tensil_driver_setup_buffer_preamble(struct tensil_driver *driver) {
    tensil_buffer_reset(&driver->buffer);

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    // Since config instructions precede the program in the buffer we
    // need to offset the program counter correspondingly in order for the
    // sample lookup to be accurate. This assumes the config instruction
    // is not advancing program counter after setting it.
    tensil_error_t error = tensil_buffer_append_config_instruction(
        &driver->buffer, &driver->layout,
        TENSIL_CONFIG_REGISTER_PROGRAM_COUNTER, PROGRAM_COUNTER_SHIFT);

    if (error)
        return error;
#endif

    return TENSIL_ERROR_NONE;
}

static tensil_error_t run_config(struct tensil_driver *driver) {
    tensil_error_t error = tensil_driver_setup_buffer_preamble(driver);

    if (error)
        return error;

    error = tensil_buffer_append_config_instruction(
        &driver->buffer, &driver->layout, TENSIL_CONFIG_REGISTER_DRAM0_OFFSET,
        TENSIL_CONFIG_DRAM_OFFSET(driver->dram0_base_ptr));

    if (error)
        return error;

    error = tensil_buffer_append_config_instruction(
        &driver->buffer, &driver->layout, TENSIL_CONFIG_REGISTER_DRAM1_OFFSET,
        TENSIL_CONFIG_DRAM_OFFSET(driver->dram1_base_ptr));

    if (error)
        return error;

#ifdef TENSIL_PLATFORM_DECODER_TIMEOUT
    error = tensil_buffer_append_config_instruction(
        &driver->buffer, &driver->layout, TENSIL_CONFIG_REGISTER_TIMEOUT,
        driver->decoder_timeout);

    if (error)
        return error;
#endif

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    error = tensil_buffer_append_config_instruction(
        &driver->buffer, &driver->layout,
        TENSIL_CONFIG_REGISTER_SAMPLE_INTERVAL, TENSIL_SAMPLE_INTERVAL_CYCLES);

    if (error)
        return error;
#endif

    error = tensil_driver_setup_buffer_postamble(driver);

    if (error)
        return error;

    return tensil_driver_run(driver, NULL);
}

tensil_error_t tensil_driver_init(struct tensil_driver *driver) {
    memset(driver, 0, sizeof(struct tensil_driver));

    driver->arch.array_size = TENSIL_ARCHITECTURE_ARRAY_SIZE;
    driver->arch.data_type = TENSIL_ARCHITECTURE_DATA_TYPE;

    driver->arch.local_depth = TENSIL_ARCHITECTURE_LOCAL_DEPTH;
    driver->arch.accumulator_depth = TENSIL_ARCHITECTURE_ACCUMULATOR_DEPTH;
    driver->arch.dram0_depth = TENSIL_ARCHITECTURE_DRAM0_DEPTH;
    driver->arch.dram1_depth = TENSIL_ARCHITECTURE_DRAM1_DEPTH;
    driver->arch.stride0_depth = TENSIL_ARCHITECTURE_STRIDE0_DEPTH;
    driver->arch.stride1_depth = TENSIL_ARCHITECTURE_STRIDE1_DEPTH;
    driver->arch.simd_registers_depth =
        TENSIL_ARCHITECTURE_SIMD_REGISTERS_DEPTH;

#ifdef TENSIL_PLATFORM_DECODER_TIMEOUT
    driver->decoder_timeout = TENSIL_PLATFORM_DECODER_TIMEOUT;
#endif

    if (!tensil_architecture_is_valid(&driver->arch)) {
        return TENSIL_DRIVER_ERROR(
            TENSIL_ERROR_DRIVER_INVALID_ARCH,
            "Invalid architecture in architecture_config.h");
    }

    tensil_instruction_layout_init(&driver->layout, &driver->arch);

#if defined(TENSIL_PLATFORM_PROG_BUFFER_BASE) &&                               \
    defined(TENSIL_PLATFORM_PROG_BUFFER_HIGH) &&                               \
    defined(TENSIL_PLATFORM_DRAM_BUFFER_BASE) &&                               \
    defined(TENSIL_PLATFORM_DRAM_BUFFER_HIGH)

    driver->buffer.ptr = (uint8_t *)TENSIL_PLATFORM_PROG_BUFFER_BASE;
    driver->buffer.offset = 0;
    driver->buffer.size =
        TENSIL_PLATFORM_PROG_BUFFER_HIGH - TENSIL_PLATFORM_PROG_BUFFER_BASE;

    if ((driver->arch.dram0_depth + driver->arch.dram1_depth) *
            driver->arch.array_size *
            tensil_dram_sizeof_scalar(driver->arch.data_type) >
        TENSIL_PLATFORM_DRAM_BUFFER_HIGH - TENSIL_PLATFORM_DRAM_BUFFER_BASE)
        return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_INSUFFICIENT_BUFFER,
                                   "Insufficient DRAM buffers");

    driver->dram0_base_ptr = (uint8_t *)TENSIL_PLATFORM_DRAM_BUFFER_BASE;
    driver->dram0_size = driver->arch.dram0_depth * driver->arch.array_size *
                         tensil_dram_sizeof_scalar(driver->arch.data_type);

    driver->dram1_base_ptr = driver->dram0_base_ptr + driver->dram0_size;
    driver->dram1_size = driver->arch.dram1_depth * driver->arch.array_size *
                         tensil_dram_sizeof_scalar(driver->arch.data_type);

#else
    return TENSIL_DRIVER_ERROR(
        TENSIL_ERROR_DRIVER_INVALID_PLATFORM,
        "Target must specify program and DRAM buffers, see platform.h");
#endif

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
#ifdef TENSIL_PLATFORM_SAMPLE_BLOCK_SIZE
    driver->sample_block_size = TENSIL_PLATFORM_SAMPLE_BLOCK_SIZE;
#else
    return TENSIL_DRIVER_ERROR(
        TENSIL_ERROR_DRIVER_INVALID_PLATFORM,
        "Target must specify sample block size, see platform.h");
#endif
#if defined(TENSIL_PLATFORM_SAMPLE_BUFFER_BASE) &&                             \
    defined(TENSIL_PLATFORM_SAMPLE_BUFFER_HIGH)

    if (TENSIL_SAMPLE_SIZE_BYTES * driver->sample_block_size >
        TENSIL_PLATFORM_SAMPLE_BUFFER_HIGH -
            TENSIL_PLATFORM_SAMPLE_BUFFER_BASE) {
        return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_INSUFFICIENT_BUFFER,
                                   "Insufficient sample buffer");
    }

    driver->sample_buffer.ptr = (uint8_t *)TENSIL_PLATFORM_SAMPLE_BUFFER_BASE;
    driver->sample_buffer.size =
        TENSIL_PLATFORM_SAMPLE_BUFFER_HIGH - TENSIL_PLATFORM_SAMPLE_BUFFER_BASE;
#else
    return TENSIL_DRIVER_ERROR(
        TENSIL_ERROR_DRIVER_INVALID_PLATFORM,
        "Target must specify sample buffers, see platform.h");
#endif
#endif

#ifdef TENSIL_PLATFORM_INSTRUCTION_AXI_DMA_DEVICE_ID
    tensil_error_t error = tensil_compute_unit_init(&driver->tcu);

    if (error)
        return error;
#else
    return TENSIL_DRIVER_ERROR(
        TENSIL_ERROR_DRIVER_INVALID_PLATFORM,
        "Target must specify instruction AXI DMA device, see platform.h");
#endif

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    error = tensil_compute_unit_init_sampling(&driver->tcu,
                                              driver->sample_block_size);

    if (error)
        return error;
#endif

    return run_config(driver);
}

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

tensil_error_t
tensil_driver_load_program_from_file(struct tensil_driver *driver, size_t size,
                                     const char *file_name) {
    tensil_error_t error = tensil_driver_setup_buffer_preamble(driver);

    if (error)
        return error;

    error = tensil_buffer_append_program_from_file(&driver->buffer, size,
                                                   file_name);

    if (error)
        return error;

    error = tensil_driver_setup_buffer_postamble(driver);

    if (error)
        return error;

    return TENSIL_ERROR_NONE;
}

tensil_error_t tensil_driver_load_dram_vectors_from_file(
    struct tensil_driver *driver, enum tensil_dram_bank dram_bank,
    size_t offset, size_t size, const char *file_name) {
    uint8_t *bank_ptr = tensil_driver_get_dram_bank_base_ptr(driver, dram_bank);
    size_t bank_size = get_dram_bank_size(driver, dram_bank);

    if ((offset + size) * tensil_dram_sizeof_scalar(driver->arch.data_type) *
            driver->arch.array_size >
        bank_size)
        return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_INSUFFICIENT_BUFFER,
                                   "Consts data too big in %s", file_name);

    return tensil_dram_write_scalars_from_file(
        bank_ptr, driver->arch.data_type, offset * driver->arch.array_size,
        size * driver->arch.array_size, file_name);
}

static tensil_error_t run_load_consts(struct tensil_driver *driver,
                                      size_t offset, size_t size) {
    tensil_error_t error = tensil_driver_setup_buffer_preamble(driver);

    if (error)
        return error;

    error = tensil_buffer_append_instruction(
        &driver->buffer, &driver->layout, TENSIL_OPCODE_DATA_MOVE,
        TENSIL_DATA_MOVE_FLAG_DRAM1_TO_LOCAL, offset, offset, size - 1);

    if (error)
        return error;

    error = tensil_driver_setup_buffer_postamble(driver);

    if (error)
        return error;

    return tensil_driver_run(driver, NULL);
}

tensil_error_t tensil_driver_load_model(struct tensil_driver *driver,
                                        const struct tensil_model *model) {
    if (!tensil_architecture_is_compatible(&driver->arch, &model->arch))
        return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_INCOMPATIBLE_MODEL,
                                   "Incompatible model");

    tensil_error_t error = TENSIL_ERROR_NONE;
    char file_name[FF_MAX_LFN];

    for (size_t i = 0; i < model->consts_size; i++) {
        strcpy(file_name, model->path);
        strcat(file_name, model->consts[i].file_name);

        error = tensil_driver_load_dram_vectors_from_file(
            driver, TENSIL_DRAM1, model->consts[i].base, model->consts[i].size,
            file_name);

        if (error)
            return error;

        if (model->load_consts_to_local) {
            error = run_load_consts(driver, model->consts[i].base,
                                    model->consts[i].size);

            if (error)
                return error;
        }
    }

    strcpy(file_name, model->path);
    strcat(file_name, model->prog.file_name);

    error = tensil_driver_load_program_from_file(driver, model->prog.size,
                                                 file_name);

    if (error)
        return error;

    return TENSIL_ERROR_NONE;
}

tensil_error_t tensil_driver_load_model_input_from_file(
    struct tensil_driver *driver, const struct tensil_model *model,
    const char *input_name, const char *file_name) {
    for (size_t i = 0; i < model->inputs_size; i++) {
        if (strcmp(model->inputs[i].name, input_name) == 0)
            // TODO: Support non-continuous inputs and outputs
            return tensil_driver_load_dram_vectors_from_file(
                driver, TENSIL_DRAM0, model->inputs[i].base,
                model->inputs[i].size, file_name);
    }

    return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_UNEXPECTED_INPUT_NAME,
                               "Unexpected input name %s", input_name);
}

#endif

tensil_error_t tensil_driver_load_model_input_scalars(
    struct tensil_driver *driver, const struct tensil_model *model,
    const char *input_name, size_t size, const float *buffer) {
    for (size_t i = 0; i < model->inputs_size; i++) {
        if (strcmp(model->inputs[i].name, input_name) == 0) {
            float *vector_buffer =
                (float *)malloc(model->inputs[i].size *
                                driver->arch.array_size * sizeof(float));

            if (!vector_buffer)
                return TENSIL_DRIVER_ERROR(
                    TENSIL_ERROR_DRIVER_OUT_OF_HEAP_MEMORY,
                    "Out of heap memory");

            for (size_t j = 0;
                 j < model->inputs[i].size * driver->arch.array_size; j++) {
                if (j < size)
                    vector_buffer[j] = buffer[j];
                else
                    vector_buffer[j] = 0.0;
            }

            tensil_driver_write_dram_vectors(
                driver, TENSIL_DRAM0, model->inputs[i].base, 0,
                model->inputs[i].size, vector_buffer);

            free(vector_buffer);

            // TODO: Support non-continuous inputs and outputs
            return TENSIL_ERROR_NONE;
        }
    }

    return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_UNEXPECTED_INPUT_NAME,
                               "Unexpected input name %s", input_name);
}

tensil_error_t tensil_driver_load_model_input_vector_scalars(
    struct tensil_driver *driver, const struct tensil_model *model,
    const char *input_name, size_t vector_offset, size_t scalars_size,
    const float *buffer) {
    for (size_t i = 0; i < model->inputs_size; i++) {
        if (strcmp(model->inputs[i].name, input_name) == 0) {
            float *vector_buffer =
                (float *)malloc(driver->arch.array_size * sizeof(float));

            if (!vector_buffer)
                return TENSIL_DRIVER_ERROR(
                    TENSIL_ERROR_DRIVER_OUT_OF_HEAP_MEMORY,
                    "Out of heap memory");

            for (size_t j = 0; j < driver->arch.array_size; j++) {
                if (j < scalars_size)
                    vector_buffer[j] = buffer[j];
                else
                    vector_buffer[j] = 0.0;
            }

            tensil_driver_write_dram_vectors(
                driver, TENSIL_DRAM0, model->inputs[i].base + vector_offset, 0,
                1, vector_buffer);

            free(vector_buffer);

            // TODO: Support non-continuous inputs and outputs
            return TENSIL_ERROR_NONE;
        }
    }

    return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_UNEXPECTED_INPUT_NAME,
                               "Unexpected input name %s", input_name);
}

tensil_error_t tensil_driver_get_model_output_scalars(
    const struct tensil_driver *driver, const struct tensil_model *model,
    const char *output_name, size_t size, float *buffer) {
    for (size_t i = 0; i < model->outputs_size; i++) {
        if (strcmp(model->outputs[i].name, output_name) == 0) {
            size_t output_size_scalars =
                model->outputs[i].size * driver->arch.array_size;
            float *vector_buffer =
                (float *)malloc(output_size_scalars * sizeof(float));

            if (!vector_buffer)
                return TENSIL_DRIVER_ERROR(
                    TENSIL_ERROR_DRIVER_OUT_OF_HEAP_MEMORY,
                    "Out of heap memory");

            tensil_driver_read_dram_vectors(
                driver, TENSIL_DRAM0, model->outputs[i].base, 0,
                model->outputs[i].size, vector_buffer);

            for (size_t j = 0; j < size; j++)
                if (j < output_size_scalars)
                    buffer[j] = vector_buffer[j];

            free(vector_buffer);

            // TODO: Support non-continuous inputs and outputs
            return TENSIL_ERROR_NONE;
        }
    }

    return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_UNEXPECTED_OUTPUT_NAME,
                               "Unexpected output name %s", output_name);
}

#ifdef TENSIL_PLATFORM_ENABLE_STDIO

#define MAX_PRINT_OUTPUT_VECTORS 16

tensil_error_t
tensil_driver_print_model_output_vectors(const struct tensil_driver *driver,
                                         const struct tensil_model *model,
                                         const char *output_name) {
    for (size_t i = 0; i < model->outputs_size; i++) {
        if (strcmp(model->outputs[i].name, output_name) == 0) {
            float *vector_buffer =
                (float *)malloc(driver->arch.array_size * sizeof(float));

            if (!vector_buffer)
                return TENSIL_DRIVER_ERROR(
                    TENSIL_ERROR_DRIVER_OUT_OF_HEAP_MEMORY,
                    "Out of heap memory");

            size_t size = model->outputs[i].size;

            if (size > MAX_PRINT_OUTPUT_VECTORS)
                size = MAX_PRINT_OUTPUT_VECTORS;

            for (size_t j = 0; j < size; j++) {
                tensil_driver_read_dram_vectors(driver, TENSIL_DRAM0,
                                                model->outputs[i].base + j, 0,
                                                1, vector_buffer);

                printf("%s[%04zu]=", output_name, j);

                for (size_t k = 0; k < driver->arch.array_size; k++)
                    printf("%9.4f ", vector_buffer[k]);

                printf("\n");
            }

            free(vector_buffer);

            // TODO: Support non-continuous inputs and outputs
            return TENSIL_ERROR_NONE;
        }
    }

    return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_UNEXPECTED_OUTPUT_NAME,
                               "Unexpected output name %s", output_name);
}

#endif

tensil_error_t tensil_driver_write_dram_vectors(struct tensil_driver *driver,
                                                enum tensil_dram_bank dram_bank,
                                                size_t offset, size_t stride,
                                                size_t size, float *buffer) {
    uint8_t *bank_ptr = tensil_driver_get_dram_bank_base_ptr(driver, dram_bank);
    size_t bank_size = get_dram_bank_size(driver, dram_bank);

    if ((offset + size * (1 << stride)) *
            tensil_dram_sizeof_scalar(driver->arch.data_type) *
            driver->arch.array_size >
        bank_size)
        return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_INSUFFICIENT_BUFFER,
                                   "Written data too big");

    if (stride == 0)
        tensil_dram_write_scalars(bank_ptr, driver->arch.data_type,
                                  offset * driver->arch.array_size,
                                  size * driver->arch.array_size, buffer);
    else
        for (size_t i = 0; i < size; i++)
            tensil_dram_write_scalars(
                bank_ptr, driver->arch.data_type,
                (offset + i * (1 << stride)) * driver->arch.array_size,
                driver->arch.array_size, buffer + i * driver->arch.array_size);

    return TENSIL_ERROR_NONE;
}

tensil_error_t
tensil_driver_read_dram_vectors(const struct tensil_driver *driver,
                                enum tensil_dram_bank dram_bank, size_t offset,
                                size_t stride, size_t size, float *buffer) {
    const uint8_t *bank_ptr =
        tensil_driver_get_dram_bank_base_ptr(driver, dram_bank);
    size_t bank_size = get_dram_bank_size(driver, dram_bank);

    if ((offset + size * (1 << stride)) *
            tensil_dram_sizeof_scalar(driver->arch.data_type) *
            driver->arch.array_size >
        bank_size)
        return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_INSUFFICIENT_BUFFER,
                                   "Read data too big");

    if (stride == 0)
        tensil_dram_read_scalars(bank_ptr, driver->arch.data_type,
                                 offset * driver->arch.array_size,
                                 size * driver->arch.array_size, buffer);

    else
        for (size_t i = 0; i < size; i++)
            tensil_dram_read_scalars(
                bank_ptr, driver->arch.data_type,
                (offset + i * (1 << stride)) * driver->arch.array_size,
                driver->arch.array_size, buffer + i * driver->arch.array_size);

    return TENSIL_ERROR_NONE;
}
