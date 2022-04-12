/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#include "driver.h"

#include <malloc.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

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

uint8_t *driver_get_dram_bank_base_ptr(const struct driver *driver,
                                       enum dram_bank dram_bank) {
    switch (dram_bank) {
    case DRAM0:
        return driver->dram0_base_ptr;
    case DRAM1:
    default:
        return driver->dram1_base_ptr;
    }
}

static size_t get_dram_bank_size(const struct driver *driver,
                                 enum dram_bank dram_bank) {
    switch (dram_bank) {
    case DRAM0:
        return driver->dram0_size;
    case DRAM1:
    default:
        return driver->dram1_size;
    }
}

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID

static error_t run_buffer_with_sampling(struct driver *driver) {
#ifdef TENSIL_PLATFORM_INSTRUCTION_AXI_DMA_DEVICE_ID

    error_t error = ERROR_NONE;
    size_t instructions_run_offset = 0;
    sample_buffer_reset(&driver->sample_buffer);

    bool instructions_busy = false;
    bool sample_busy = false;

    while (instructions_run_offset != driver->buffer.offset) {
        if (!instructions_busy) {
            error = tcu_start_instructions(&driver->tcu, &driver->buffer,
                                           &instructions_run_offset);

            if (error)
                return error;
        }

        if (!sample_busy) {
            error = tcu_start_sampling(&driver->tcu, &driver->sample_buffer);

            if (error)
                return error;
        }

        do {
            sample_busy = tcu_is_sample_busy(&driver->tcu);
            instructions_busy = tcu_is_instructions_busy(&driver->tcu);
        } while (sample_busy && instructions_busy);

        if (!sample_busy)
            tcu_complete_sampling(&driver->tcu, &driver->sample_buffer);
    }

    while (tcu_is_instructions_busy(&driver->tcu)) {
        if (!sample_busy) {
            error = tcu_start_sampling(&driver->tcu, &driver->sample_buffer);

            if (error)
                return error;
        }

        sample_busy = tcu_is_sample_busy(&driver->tcu);

        if (!sample_busy)
            tcu_complete_sampling(&driver->tcu, &driver->sample_buffer);
    }

    if (sample_busy) {
        while (tcu_is_sample_busy(&driver->tcu))
            ;

        tcu_complete_sampling(&driver->tcu, &driver->sample_buffer);
    }

    return ERROR_NONE;
#else
    return DRIVER_ERROR(
        ERROR_DRIVER_INVALID_PLATFORM,
        "Target must specify instruction AXI DMA device, see platform.h");
#endif
}

#else

static error_t run_buffer(struct tcu *tcu,
                          const struct instruction_buffer *buffer) {
#ifdef TENSIL_PLATFORM_INSTRUCTION_AXI_DMA_DEVICE_ID

    error_t error = ERROR_NONE;
    size_t instructions_run_offset = 0;

    while (instructions_run_offset != buffer->offset) {
        error = tcu_start_instructions(tcu, buffer, &instructions_run_offset);

        if (error)
            return error;

        while (tcu_is_instructions_busy(tcu))
            ;
    }

    return ERROR_NONE;
#else
    return DRIVER_ERROR(
        ERROR_DRIVER_INVALID_PLATFORM,
        "Target must specify instruction AXI DMA device, see platform.h");
#endif
}

#endif

static void fill_dram_vectors(struct driver *driver, enum dram_bank dram_bank,
                              size_t offset, int byte, size_t size) {
    uint8_t *bank_ptr = driver_get_dram_bank_base_ptr(driver, dram_bank);

    dram_fill_scalars(bank_ptr, driver->arch.data_type,
                      offset * driver->arch.array_size, byte,
                      size * driver->arch.array_size);
}

static int compare_dram_vectors(struct driver *driver, enum dram_bank dram_bank,
                                size_t offset0, size_t offset1, size_t size) {
    uint8_t *bank_ptr = driver_get_dram_bank_base_ptr(driver, dram_bank);

    return dram_compare_scalars(
        bank_ptr, driver->arch.data_type, offset0 * driver->arch.array_size,
        offset1 * driver->arch.array_size, size * driver->arch.array_size);
}

static error_t append_flush_instructions(struct driver *driver) {
    size_t probe_source_offset = driver->arch.dram0_depth - 1;
    size_t probe_target_offset = driver->arch.dram0_depth - 2;

    error_t error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_DRAM0_TO_LOCAL, 0, probe_source_offset, 0);

    if (error)
        return error;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_LOCAL_TO_DRAM0, 0, probe_target_offset, 0);

    return error;
}

static void reset_flush_probe(struct driver *driver) {
    size_t probe_source_offset = driver->arch.dram0_depth - 1;
    size_t probe_target_offset = driver->arch.dram0_depth - 2;

    fill_dram_vectors(driver, DRAM0, probe_source_offset, 0, 1);
    fill_dram_vectors(driver, DRAM0, probe_target_offset, 0xff, 1);
}

static void wait_for_flush(struct driver *driver) {
    size_t probe_source_offset = driver->arch.dram0_depth - 1;
    size_t probe_target_offset = driver->arch.dram0_depth - 2;

    while (compare_dram_vectors(driver, DRAM0, probe_source_offset,
                                probe_target_offset, 1) != 0)
        ;
}

error_t driver_run(struct driver *driver, const struct run_opts *run_opts) {
    reset_flush_probe(driver);

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    error_t error = run_buffer_with_sampling(driver);
#else
    error_t error = run_buffer(&driver->tcu, &driver->buffer);
#endif

    if (error)
        return error;

    wait_for_flush(driver);

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID

#ifdef TENSIL_PLATFORM_ENABLE_PRINTF
    if (run_opts && (run_opts->print_sampling_summary ||
                     run_opts->print_sampling_aggregates ||
                     run_opts->print_sampling_listing)) {
        error_t error = sample_buffer_print_analysis(
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
        error_t error =
            sample_buffer_to_file(&driver->sample_buffer, &driver->buffer,
                                  &driver->layout, run_opts->sample_file_name);

        if (error)
            return error;
    }
#endif

#endif
    return ERROR_NONE;
}

error_t driver_setup_buffer_postamble(struct driver *driver) {
    error_t error = append_flush_instructions(driver);

    if (error)
        return error;

#ifdef TENSIL_PLATFORM_INSTRUCTION_AXI_DMA_DEVICE_ID
    error = buffer_pad_to_alignment(
        &driver->buffer, &driver->layout,
        tcu_get_instructions_data_width_bytes(&driver->tcu));

    if (error)
        return error;
#endif

    return ERROR_NONE;
}

error_t driver_setup_buffer_preamble(struct driver *driver) {
    buffer_reset(&driver->buffer);

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    // Since config instructions precede the program in the buffer we
    // need to offset the program counter correspondingly in order for the
    // sample lookup to be accurate. This assumes the config instruction
    // is not advancing program counter after setting it.
    error_t error = buffer_append_config_instruction(
        &driver->buffer, &driver->layout, CONFIG_REGISTER_PROGRAM_COUNTER,
        PROGRAM_COUNTER_SHIFT);

    if (error)
        return error;
#endif

    return ERROR_NONE;
}

static error_t run_config(struct driver *driver) {
    error_t error = driver_setup_buffer_preamble(driver);

    if (error)
        return error;

    error = buffer_append_config_instruction(
        &driver->buffer, &driver->layout, CONFIG_REGISTER_DRAM0_OFFSET,
        CONFIG_DRAM_OFFSET(driver->dram0_base_ptr));

    if (error)
        return error;

    error = buffer_append_config_instruction(
        &driver->buffer, &driver->layout, CONFIG_REGISTER_DRAM1_OFFSET,
        CONFIG_DRAM_OFFSET(driver->dram1_base_ptr));

    if (error)
        return error;

    error = buffer_append_config_instruction(&driver->buffer, &driver->layout,
                                             CONFIG_REGISTER_TIMEOUT,
                                             driver->decoder_timeout);

    if (error)
        return error;

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    error = buffer_append_config_instruction(&driver->buffer, &driver->layout,
                                             CONFIG_REGISTER_SAMPLE_INTERVAL,
                                             SAMPLE_INTERVAL_CYCLES);

    if (error)
        return error;
#endif

#ifdef TENSIL_PLATFORM_INSTRUCTION_AXI_DMA_DEVICE_ID
    error = buffer_pad_to_alignment(
        &driver->buffer, &driver->layout,
        tcu_get_instructions_data_width_bytes(&driver->tcu));

    if (error)
        return error;
#endif

    error = driver_setup_buffer_postamble(driver);

    if (error)
        return error;

    return driver_run(driver, NULL);
}

error_t driver_init(struct driver *driver) {
    memset(driver, 0, sizeof(struct driver));

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

    driver->decoder_timeout = TENSIL_PLATFORM_DECODER_TIMEOUT;

    if (!architecture_is_valid(&driver->arch)) {
        return DRIVER_ERROR(ERROR_DRIVER_INVALID_ARCH,
                            "Invalid architecture in architecture_config.h");
    }

    instruction_layout_init(&driver->layout, &driver->arch);

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
            dram_sizeof_scalar(driver->arch.data_type) >
        TENSIL_PLATFORM_DRAM_BUFFER_HIGH - TENSIL_PLATFORM_DRAM_BUFFER_BASE)
        return DRIVER_ERROR(ERROR_DRIVER_INSUFFICIENT_BUFFER,
                            "Insufficient DRAM buffers");

    driver->dram0_base_ptr = (uint8_t *)TENSIL_PLATFORM_DRAM_BUFFER_BASE;
    driver->dram0_size = driver->arch.dram0_depth * driver->arch.array_size *
                         dram_sizeof_scalar(driver->arch.data_type);

    driver->dram1_base_ptr = driver->dram0_base_ptr + driver->dram0_size;
    driver->dram1_size = driver->arch.dram1_depth * driver->arch.array_size *
                         dram_sizeof_scalar(driver->arch.data_type);

#else
    return DRIVER_ERROR(
        ERROR_DRIVER_INVALID_PLATFORM,
        "Target must specify program and DRAM buffers, see platform.h");
#endif

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
#ifdef TENSIL_PLATFORM_SAMPLE_BLOCK_SIZE
    driver->sample_block_size = TENSIL_PLATFORM_SAMPLE_BLOCK_SIZE;
#else
    return DRIVER_ERROR(
        ERROR_DRIVER_INVALID_PLATFORM,
        "Target must specify sample block size, see platform.h");
#endif
#if defined(TENSIL_PLATFORM_SAMPLE_BUFFER_BASE) &&                             \
    defined(TENSIL_PLATFORM_SAMPLE_BUFFER_HIGH)

    if (SAMPLE_SIZE_BYTES * driver->sample_block_size >
        TENSIL_PLATFORM_SAMPLE_BUFFER_HIGH -
            TENSIL_PLATFORM_SAMPLE_BUFFER_BASE) {
        return DRIVER_ERROR(ERROR_DRIVER_INSUFFICIENT_BUFFER,
                            "Insufficient sample buffer");
    }

    driver->sample_buffer.ptr = (uint8_t *)TENSIL_PLATFORM_SAMPLE_BUFFER_BASE;
    driver->sample_buffer.size =
        TENSIL_PLATFORM_SAMPLE_BUFFER_HIGH - TENSIL_PLATFORM_SAMPLE_BUFFER_BASE;
#else
    return DRIVER_ERROR(ERROR_DRIVER_INVALID_PLATFORM,
                        "Target must specify sample buffers, see platform.h");
#endif
#endif

#ifdef TENSIL_PLATFORM_INSTRUCTION_AXI_DMA_DEVICE_ID
    error_t error = tcu_init(&driver->tcu);

    if (error)
        return error;
#else
    return DRIVER_ERROR(
        ERROR_DRIVER_INVALID_PLATFORM,
        "Target must specify instruction AXI DMA device, see platform.h");
#endif

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    error = tcu_init_sampling(&driver->tcu, driver->sample_block_size);

    if (error)
        return error;
#endif

    return run_config(driver);
}

#ifdef TENSIL_PLATFORM_FLASH_READ

error_t driver_load_program_from_flash(struct driver *driver, size_t size,
                                       TENSIL_PLATFORM_FLASH_TYPE flash) {
    error_t error = driver_setup_buffer_preamble(driver);

    if (error)
        return error;

    error = buffer_append_program_from_flash(&driver->buffer, size, flash);

    if (error)
        return error;

    error = driver_setup_buffer_postamble(driver);

    if (error)
        return error;

    return ERROR_NONE;
}

#endif

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

error_t driver_load_program_from_file(struct driver *driver, size_t size,
                                      const char *file_name) {
    error_t error = driver_setup_buffer_preamble(driver);

    if (error)
        return error;

    error = buffer_append_program_from_file(&driver->buffer, size, file_name);

    if (error)
        return error;

    error = driver_setup_buffer_postamble(driver);

    if (error)
        return error;

    return ERROR_NONE;
}

error_t driver_load_dram_vectors_from_file(struct driver *driver,
                                           enum dram_bank dram_bank,
                                           size_t offset, size_t size,
                                           const char *file_name) {
    uint8_t *bank_ptr = driver_get_dram_bank_base_ptr(driver, dram_bank);
    size_t bank_size = get_dram_bank_size(driver, dram_bank);

    if ((offset + size) * dram_sizeof_scalar(driver->arch.data_type) *
            driver->arch.array_size >
        bank_size)
        return DRIVER_ERROR(ERROR_DRIVER_INSUFFICIENT_BUFFER,
                            "Consts data too big in %s", file_name);

    return dram_write_scalars_from_file(
        bank_ptr, driver->arch.data_type, offset * driver->arch.array_size,
        size * driver->arch.array_size, file_name);
}

error_t driver_load_model(struct driver *driver, const struct model *model) {
    if (!architecture_is_compatible(&driver->arch, &model->arch))
        return DRIVER_ERROR(ERROR_DRIVER_INCOMPATIBLE_MODEL,
                            "Incompatible model");

    error_t error = driver_load_program_from_file(driver, model->prog.size,
                                                  model->prog.file_name);
    if (error)
        return error;

    for (size_t i = 0; i < model->consts_size; i++) {
        error = driver_load_dram_vectors_from_file(
            driver, DRAM1, model->consts[i].base, model->consts[i].size,
            model->consts[i].file_name);

        if (error)
            return error;
    }

    return ERROR_NONE;
}

error_t driver_load_model_input_from_file(struct driver *driver,
                                          const struct model *model,
                                          const char *input_name,
                                          const char *file_name) {
    for (size_t i = 0; i < model->inputs_size; i++) {
        if (strcmp(model->inputs[i].name, input_name) == 0)
            // TODO: Support non-continuous inputs and outputs
            return driver_load_dram_vectors_from_file(
                driver, DRAM0, model->inputs[i].base, model->inputs[i].size,
                file_name);
    }

    return DRIVER_ERROR(ERROR_DRIVER_UNEXPECTED_INPUT_NAME,
                        "Unexpected input name %s", input_name);
}

#endif

#ifdef TENSIL_PLATFORM_FLASH_READ

error_t driver_load_model_from_flash(struct driver *driver,
                                     const struct model *model,
                                     TENSIL_PLATFORM_FLASH_TYPE flash) {
    if (!architecture_is_compatible(&driver->arch, &model->arch))
        return DRIVER_ERROR(ERROR_DRIVER_INCOMPATIBLE_MODEL,
                            "Incompatible model");

    error_t error =
        driver_load_program_from_flash(driver, model->prog.size, flash);
    if (error)
        return error;

    for (size_t i = 0; i < model->consts_size; i++) {
        error = driver_load_dram_vectors_from_flash(
            driver, DRAM1, model->consts[i].base, model->consts[i].size, flash);

        if (error)
            return error;
    }

    return ERROR_NONE;
}

error_t driver_load_dram_vectors_from_flash(struct driver *driver,
                                            enum dram_bank dram_bank,
                                            size_t offset, size_t size,
                                            TENSIL_PLATFORM_FLASH_TYPE flash) {
    uint8_t *bank_ptr = driver_get_dram_bank_base_ptr(driver, dram_bank);
    size_t bank_size = get_dram_bank_size(driver, dram_bank);

    if ((offset + size) * dram_sizeof_scalar(driver->arch.data_type) *
            driver->arch.array_size >
        bank_size)
        return DRIVER_ERROR(ERROR_DRIVER_INSUFFICIENT_BUFFER,
                            "Consts data too big");

    return dram_write_scalars_from_flash(bank_ptr, driver->arch.data_type,
                                         offset * driver->arch.array_size,
                                         size * driver->arch.array_size, flash);
}

error_t driver_load_model_input_from_flash(struct driver *driver,
                                           const struct model *model,
                                           const char *input_name,
                                           TENSIL_PLATFORM_FLASH_TYPE flash) {
    for (size_t i = 0; i < model->inputs_size; i++) {
        if (strcmp(model->inputs[i].name, input_name) == 0)
            // TODO: Support non-continuous inputs and outputs
            return driver_load_dram_vectors_from_flash(
                driver, DRAM0, model->inputs[i].base, model->inputs[i].size,
                flash);
    }

    return DRIVER_ERROR(ERROR_DRIVER_UNEXPECTED_INPUT_NAME,
                        "Unexpected input name %s", input_name);
}

#endif

error_t driver_load_model_input_scalars(struct driver *driver,
                                        const struct model *model,
                                        const char *input_name, size_t size,
                                        const float *buffer) {
    for (size_t i = 0; i < model->inputs_size; i++) {
        if (strcmp(model->inputs[i].name, input_name) == 0) {
            float *vector_buffer =
                (float *)malloc(model->inputs[i].size *
                                driver->arch.array_size * sizeof(float));

            if (!vector_buffer)
                return DRIVER_ERROR(ERROR_DRIVER_OUT_OF_HEAP_MEMORY,
                                    "Out of heap memory");

            for (size_t j = 0;
                 j < model->inputs[i].size * driver->arch.array_size; j++) {
                if (j < size)
                    vector_buffer[j] = buffer[j];
                else
                    vector_buffer[j] = 0.0;
            }

            driver_write_dram_vectors(driver, DRAM0, model->inputs[i].base, 0,
                                      model->inputs[i].size, vector_buffer);

            free(vector_buffer);

            // TODO: Support non-continuous inputs and outputs
            return ERROR_NONE;
        }
    }

    return DRIVER_ERROR(ERROR_DRIVER_UNEXPECTED_INPUT_NAME,
                        "Unexpected input name %s", input_name);
}

error_t driver_load_model_input_vector_scalars(
    struct driver *driver, const struct model *model, const char *input_name,
    size_t vector_offset, size_t scalars_size, const float *buffer) {
    for (size_t i = 0; i < model->inputs_size; i++) {
        if (strcmp(model->inputs[i].name, input_name) == 0) {
            float *vector_buffer =
                (float *)malloc(driver->arch.array_size * sizeof(float));

            if (!vector_buffer)
                return DRIVER_ERROR(ERROR_DRIVER_OUT_OF_HEAP_MEMORY,
                                    "Out of heap memory");

            for (size_t j = 0; j < driver->arch.array_size; j++) {
                if (j < scalars_size)
                    vector_buffer[j] = buffer[j];
                else
                    vector_buffer[j] = 0.0;
            }

            driver_write_dram_vectors(driver, DRAM0,
                                      model->inputs[i].base + vector_offset, 0,
                                      1, vector_buffer);

            free(vector_buffer);

            // TODO: Support non-continuous inputs and outputs
            return ERROR_NONE;
        }
    }

    return DRIVER_ERROR(ERROR_DRIVER_UNEXPECTED_INPUT_NAME,
                        "Unexpected input name %s", input_name);
}

error_t driver_get_model_output_scalars(const struct driver *driver,
                                        const struct model *model,
                                        const char *output_name, size_t size,
                                        float *buffer) {
    for (size_t i = 0; i < model->outputs_size; i++) {
        if (strcmp(model->outputs[i].name, output_name) == 0) {
            size_t output_size_scalars =
                model->outputs[i].size * driver->arch.array_size;
            float *vector_buffer =
                (float *)malloc(output_size_scalars * sizeof(float));

            if (!vector_buffer)
                return DRIVER_ERROR(ERROR_DRIVER_OUT_OF_HEAP_MEMORY,
                                    "Out of heap memory");

            driver_read_dram_vectors(driver, DRAM0, model->outputs[i].base, 0,
                                     model->outputs[i].size, vector_buffer);

            for (size_t j = 0; j < size; j++)
                if (j < output_size_scalars)
                    buffer[j] = vector_buffer[j];

            free(vector_buffer);

            // TODO: Support non-continuous inputs and outputs
            return ERROR_NONE;
        }
    }

    return DRIVER_ERROR(ERROR_DRIVER_UNEXPECTED_OUTPUT_NAME,
                        "Unexpected output name %s", output_name);
}

#ifdef TENSIL_PLATFORM_ENABLE_PRINTF

#define MAX_PRINT_OUTPUT_VECTORS 16

error_t driver_print_model_output_vectors(const struct driver *driver,
                                          const struct model *model,
                                          const char *output_name) {
    for (size_t i = 0; i < model->outputs_size; i++) {
        if (strcmp(model->outputs[i].name, output_name) == 0) {
            float *vector_buffer =
                (float *)malloc(driver->arch.array_size * sizeof(float));

            if (!vector_buffer)
                return DRIVER_ERROR(ERROR_DRIVER_OUT_OF_HEAP_MEMORY,
                                    "Out of heap memory");

            size_t size = model->outputs[i].size;

            if (size > MAX_PRINT_OUTPUT_VECTORS)
                size = MAX_PRINT_OUTPUT_VECTORS;

            for (size_t j = 0; j < size; j++) {
                driver_read_dram_vectors(driver, DRAM0,
                                         model->outputs[i].base + j, 0, 1,
                                         vector_buffer);

                printf("%s[%04zu]=", output_name, j);

                for (size_t k = 0; k < driver->arch.array_size; k++)
                    printf("%9.4f ", vector_buffer[k]);

                printf("\n");
            }

            free(vector_buffer);

            // TODO: Support non-continuous inputs and outputs
            return ERROR_NONE;
        }
    }

    return DRIVER_ERROR(ERROR_DRIVER_UNEXPECTED_OUTPUT_NAME,
                        "Unexpected output name %s", output_name);
}

#endif

error_t driver_write_dram_vectors(struct driver *driver,
                                  enum dram_bank dram_bank, size_t offset,
                                  size_t stride, size_t size, float *buffer) {
    uint8_t *bank_ptr = driver_get_dram_bank_base_ptr(driver, dram_bank);
    size_t bank_size = get_dram_bank_size(driver, dram_bank);

    if ((offset + size * (1 << stride)) *
            dram_sizeof_scalar(driver->arch.data_type) *
            driver->arch.array_size >
        bank_size)
        return DRIVER_ERROR(ERROR_DRIVER_INSUFFICIENT_BUFFER,
                            "Written data too big");

    if (stride == 0)
        dram_write_scalars(bank_ptr, driver->arch.data_type,
                           offset * driver->arch.array_size,
                           size * driver->arch.array_size, buffer);
    else
        for (size_t i = 0; i < size; i++)
            dram_write_scalars(
                bank_ptr, driver->arch.data_type,
                (offset + i * (1 << stride)) * driver->arch.array_size,
                driver->arch.array_size, buffer + i * driver->arch.array_size);

    return ERROR_NONE;
}

error_t driver_read_dram_vectors(const struct driver *driver,
                                 enum dram_bank dram_bank, size_t offset,
                                 size_t stride, size_t size, float *buffer) {
    const uint8_t *bank_ptr = driver_get_dram_bank_base_ptr(driver, dram_bank);
    size_t bank_size = get_dram_bank_size(driver, dram_bank);

    if ((offset + size * (1 << stride)) *
            dram_sizeof_scalar(driver->arch.data_type) *
            driver->arch.array_size >
        bank_size)
        return DRIVER_ERROR(ERROR_DRIVER_INSUFFICIENT_BUFFER,
                            "Read data too big");

    if (stride == 0)
        dram_read_scalars(bank_ptr, driver->arch.data_type,
                          offset * driver->arch.array_size,
                          size * driver->arch.array_size, buffer);

    else
        for (size_t i = 0; i < size; i++)
            dram_read_scalars(
                bank_ptr, driver->arch.data_type,
                (offset + i * (1 << stride)) * driver->arch.array_size,
                driver->arch.array_size, buffer + i * driver->arch.array_size);

    return ERROR_NONE;
}
