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
#include "stopwatch.h"
#include "tcu.h"

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
#include "ff.h"
#endif

#include "../architecture_params.h"

#define PROGRAM_COUNTER_SHIFT 1

static uint8_t *get_dram_bank_base_ptr(const struct driver *driver,
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

static error_t run_config(struct driver *driver) {
    buffer_reset(&driver->buffer);

    error_t error = buffer_append_config_instruction(
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

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    return run_buffer_with_sampling(driver);
#else
    return run_buffer(&driver->tcu, &driver->buffer);
#endif
}

static void fill_dram_vectors(struct driver *driver, enum dram_bank dram_bank,
                              size_t offset, int byte, size_t size) {
    uint8_t *bank_ptr = get_dram_bank_base_ptr(driver, dram_bank);

    dram_fill_scalars(bank_ptr, driver->arch.data_type,
                      offset * driver->arch.array_size, byte,
                      size * driver->arch.array_size);
}

static int compare_dram_vectors(struct driver *driver, enum dram_bank dram_bank,
                                size_t offset0, size_t offset1, size_t size) {
    uint8_t *bank_ptr = get_dram_bank_base_ptr(driver, dram_bank);

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

static error_t setup_buffer_postamble(struct driver *driver) {
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

static error_t setup_buffer_preamble(struct driver *driver) {
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

#ifdef TENSIL_PLATFORM_FLASH_READ

error_t driver_load_program_from_flash(struct driver *driver, size_t size,
                                       TENSIL_PLATFORM_FLASH_TYPE flash) {
    printf("Loading program from flash...\n");

    error_t error = setup_buffer_preamble(driver);

    if (error)
        return error;

    error = buffer_append_program_from_flash(&driver->buffer, size, flash);

    if (error)
        return error;

    error = setup_buffer_postamble(driver);

    if (error)
        return error;

    return ERROR_NONE;
}

#endif

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

error_t driver_load_program_from_file(struct driver *driver, size_t size,
                                      const char *file_name) {
    printf("Loading program from %s...\n", file_name);

    error_t error = setup_buffer_preamble(driver);

    if (error)
        return error;

    error = buffer_append_program_from_file(&driver->buffer, size, file_name);

    if (error)
        return error;

    error = setup_buffer_postamble(driver);

    if (error)
        return error;

    return ERROR_NONE;
}

error_t driver_load_dram_vectors_from_file(struct driver *driver,
                                           enum dram_bank dram_bank,
                                           size_t offset, size_t size,
                                           const char *file_name) {
    printf("Loading DRAM%d from %s...\n", dram_bank, file_name);

    uint8_t *bank_ptr = get_dram_bank_base_ptr(driver, dram_bank);
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
    printf("Loading DRAM%d from flash...\n", dram_bank);

    uint8_t *bank_ptr = get_dram_bank_base_ptr(driver, dram_bank);
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

error_t driver_write_dram_vectors(struct driver *driver,
                                  enum dram_bank dram_bank, size_t offset,
                                  size_t stride, size_t size, float *buffer) {
    uint8_t *bank_ptr = get_dram_bank_base_ptr(driver, dram_bank);
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
    const uint8_t *bank_ptr = get_dram_bank_base_ptr(driver, dram_bank);
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

error_t driver_run(struct driver *driver, bool print_timing,
                   bool print_sampling_summary, bool print_sampling_aggregates,
                   bool print_sampling_listing) {
    struct stopwatch sw;

    error_t error = stopwatch_start(&sw);

    if (error)
        return error;

    reset_flush_probe(driver);

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    error = run_buffer_with_sampling(driver);
#else
    error = run_buffer(&driver->tcu, &driver->buffer);
#endif

    wait_for_flush(driver);

    stopwatch_stop(&sw);

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    if (print_sampling_summary || print_sampling_aggregates ||
        print_sampling_listing) {
        sample_buffer_before_read(&driver->sample_buffer);

        error_t error = sample_buffer_print_analysis(
            &driver->sample_buffer, &driver->buffer, &driver->layout,
            print_sampling_summary, print_sampling_aggregates,
            print_sampling_listing, PROGRAM_COUNTER_SHIFT);

        if (error)
            return error;
    }
#endif

    if (error)
        return error;

    if (print_timing)
        printf("Program run took %.2f us\n", stopwatch_elapsed_us(&sw));

    return ERROR_NONE;
}

static const char *ok = "\033[38;2;0;255;00mOK\033[39m";
static const char *failed = "\033[38;2;255;0;00mFAILED\033[39m";

static void write_dram_random_vectors(struct driver *driver,
                                      enum dram_bank dram_bank, size_t offset,
                                      size_t stride, size_t size) {
    uint8_t *bank_ptr = get_dram_bank_base_ptr(driver, dram_bank);

    if (stride == 0)
        dram_write_random_scalars(bank_ptr, driver->arch.data_type,
                                  offset * driver->arch.array_size,
                                  size * driver->arch.array_size);
    else
        for (size_t i = 0; i < size; i++)
            dram_write_random_scalars(bank_ptr, driver->arch.data_type,
                                      (offset + i * (1 << stride)) *
                                          driver->arch.array_size,
                                      driver->arch.array_size);
}

#define TEST_MAX_BAD_INDEXES_SIZE 32

#define MAX(a, b) (((a) > (b)) ? (a) : (b))
#define MIN(a, b) (((a) < (b)) ? (a) : (b))

static error_t do_memory_test(struct driver *driver, enum dram_bank from_bank,
                              size_t from_offset, float *from_buffer,
                              enum dram_bank to_bank, size_t to_offset,
                              float *to_buffer, size_t size, size_t stride0,
                              size_t stride1, size_t *failure_count,
                              size_t *test_count, bool verbose) {
    if (from_offset + size * (1 << MAX(stride0, stride1)) >
            driver->arch.local_depth ||
        to_offset + size * (1 << MAX(stride0, stride1)) >
            driver->arch.local_depth ||
        to_offset + size * (1 << MAX(stride0, stride1)) >
            driver->arch.accumulator_depth)
        return ERROR_NONE;

    uint8_t from_flags;
    switch (from_bank) {
    case DRAM0:
    default:
        from_flags = DATA_MOVE_FLAG_DRAM0_TO_LOCAL;
        break;

    case DRAM1:
        from_flags = DATA_MOVE_FLAG_DRAM1_TO_LOCAL;
        break;
    }

    uint8_t to_flags;
    switch (to_bank) {
    case DRAM0:
    default:
        to_flags = DATA_MOVE_FLAG_LOCAL_TO_DRAM0;
        break;

    case DRAM1:
        to_flags = DATA_MOVE_FLAG_LOCAL_TO_DRAM1;
        break;
    }

    write_dram_random_vectors(driver, from_bank, from_offset, stride1, size);
    driver_read_dram_vectors(driver, from_bank, from_offset, stride1, size,
                             from_buffer);

    error_t error = setup_buffer_preamble(driver);

    if (error)
        return error;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE, from_flags,
        instruction_make_operand0(&driver->layout, from_offset, stride0),
        instruction_make_operand1(&driver->layout, from_offset, stride1),
        size - 1);

    if (error)
        return error;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_LOCAL_TO_ACC,
        instruction_make_operand0(&driver->layout, from_offset, stride0),
        instruction_make_operand1(&driver->layout, from_offset, stride1),
        size - 1);

    if (error)
        return error;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_ACC_TO_LOCAL,
        instruction_make_operand0(&driver->layout, to_offset, stride0),
        instruction_make_operand1(&driver->layout, from_offset, stride1),
        size - 1);

    if (error)
        return error;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE, to_flags,
        instruction_make_operand0(&driver->layout, to_offset, stride0),
        instruction_make_operand1(&driver->layout, to_offset, stride1),
        size - 1);

    if (error)
        return error;

    error = setup_buffer_postamble(driver);

    if (error)
        return error;

    error = driver_run(driver, false, false, false, false);

    if (error)
        return error;

    driver_read_dram_vectors(driver, to_bank, to_offset, stride1, size,
                             to_buffer);

    size_t bad_indexes[TEST_MAX_BAD_INDEXES_SIZE];
    size_t bad_indexes_size = 0;

    for (size_t k = 0; k < size * driver->arch.array_size; k++)
        if (from_buffer[k] != to_buffer[k]) {
            bad_indexes[bad_indexes_size++] = k;

            if (bad_indexes_size == TEST_MAX_BAD_INDEXES_SIZE)
                break;
        }

    if (bad_indexes_size) {
        (*failure_count)++;

        if (verbose) {
            printf("%s moving %zu vectors from %zu to %zu:\n", failed, size,
                   from_offset, to_offset);

            for (size_t k = 0; k < bad_indexes_size; k++) {
                size_t bad_index = bad_indexes[k];

                printf("\t[%zu]%f!=[%zu]%f\n",
                       (from_offset)*driver->arch.array_size + bad_index,
                       from_buffer[bad_index],
                       (to_offset)*driver->arch.array_size + bad_index,
                       to_buffer[bad_index]);
            }
        }
    }

    (*test_count)++;

    return ERROR_NONE;
}

#define MEMORY_TEST_MIN_SIZE 8
#define MEMORY_TEST_MAX_SIZE (driver->arch.accumulator_depth)
#define MEMORY_TEST_UNTIL_OFFSET (driver->arch.accumulator_depth)
#define MEMORY_TEST_UNTIL_SHIFT 4
#define MEMORY_TEST_UNTIL_STRIDE0 (driver->arch.stride0_depth)
#define MEMORY_TEST_UNTIL_STRIDE1 (driver->arch.stride1_depth)

error_t driver_run_memory_test(struct driver *driver, enum dram_bank from_bank,
                               enum dram_bank to_bank, bool verbose) {
    error_t error = ERROR_NONE;
    float *from_buffer = (float *)malloc(
        MEMORY_TEST_MAX_SIZE * driver->arch.array_size * sizeof(float));
    float *to_buffer = (float *)malloc(MEMORY_TEST_MAX_SIZE *
                                       driver->arch.array_size * sizeof(float));

    if (!from_buffer || !to_buffer) {
        error =
            DRIVER_ERROR(ERROR_DRIVER_OUT_OF_HEAP_MEMORY, "Out of heap memory");
        goto cleanup;
    }

    for (size_t size_center = MEMORY_TEST_MIN_SIZE;
         size_center <= MEMORY_TEST_MAX_SIZE; size_center *= 2)
        for (size_t size =
                 size_center == MEMORY_TEST_MIN_SIZE ? 1 : size_center - 1;
             size <= MIN(MEMORY_TEST_MAX_SIZE, size_center + 1); size++) {
            size_t failure_count = 0;
            size_t test_count = 0;

            if (size > 0) {
                printf("%06zu vectors -----------------------\n\tStrides test ",
                       size);
                fflush(stdout);

                for (size_t stride0 = 0; stride0 < MEMORY_TEST_UNTIL_STRIDE0;
                     stride0++)
                    for (size_t stride1 = 0;
                         stride1 < MEMORY_TEST_UNTIL_STRIDE1; stride1++)
                        for (size_t from_offset = 0;
                             from_offset < MEMORY_TEST_UNTIL_SHIFT;
                             from_offset++)
                            for (size_t to_offset = 0;
                                 to_offset < MEMORY_TEST_UNTIL_SHIFT;
                                 to_offset++) {
                                error = do_memory_test(
                                    driver, from_bank, from_offset, from_buffer,
                                    to_bank, to_offset, to_buffer, size,
                                    stride0, stride1, &failure_count,
                                    &test_count, verbose);

                                if (error)
                                    goto cleanup;
                            }

                printf("%s: %zu tests, %zu failures\n",
                       failure_count ? failed : ok, test_count, failure_count);
            }

            printf("\tOffsets test ");
            fflush(stdout);

            for (size_t offset = 0; offset < MEMORY_TEST_UNTIL_OFFSET; offset++)
                for (size_t from_shift = 0;
                     from_shift < MEMORY_TEST_UNTIL_SHIFT; from_shift++)
                    for (size_t to_shift = 0;
                         to_shift < MEMORY_TEST_UNTIL_SHIFT; to_shift++) {
                        size_t from_offset = offset + from_shift;
                        size_t to_offset = offset + to_shift;

                        error = do_memory_test(
                            driver, from_bank, from_offset, from_buffer,
                            to_bank, to_offset, to_buffer, size, 0, 0,
                            &failure_count, &test_count, verbose);

                        if (error)
                            goto cleanup;
                    }

            printf("%s: %zu tests, %zu failures\n", failure_count ? failed : ok,
                   test_count, failure_count);
        }

cleanup:
    free(from_buffer);
    free(to_buffer);

    return error;
}

static float saturate(enum data_type type, float x) {
    float max = dram_max_scalar(type);
    float min = dram_min_scalar(type);

    return x > max ? max : x < min ? min : x;
}

static bool compare_scalars(enum data_type type, float expected, float actual) {
    float max_error = dram_max_error_scalar(type);

    return fabs(expected - actual) > max_error;
}

#define ARRAY_TEST_SIZE (driver->arch.accumulator_depth)
#define ARRAY_TEST_IDENTITY_WEIGHT 3.456
#define ARRAY_TEST_BIAS 78.912

#define ARRAY_TEST_INPUT_DRAM0_ADDRESS 0
#define ARRAY_TEST_INPUT_LOCAL_ADDRESS 0

#define ARRAY_TEST_OUTPUT_ACC_ADDRESS 0
#define ARRAY_TEST_OUTPUT_LOCAL_ADDRESS ARRAY_TEST_SIZE
#define ARRAY_TEST_OUTPUT_DRAM0_ADDRESS ARRAY_TEST_SIZE

#define ARRAY_TEST_WEIGHTS_DRAM1_ADDRESS 0
#define ARRAY_TEST_WEIGHTS_LOCAL_ADDRESS (ARRAY_TEST_SIZE * 2)

error_t driver_run_array_test(struct driver *driver, bool verbose) {
    error_t error = ERROR_NONE;
    size_t bad_indexes[TEST_MAX_BAD_INDEXES_SIZE];
    size_t bad_indexes_size = 0;

    float *from_buffer = (float *)malloc(
        ARRAY_TEST_SIZE * driver->arch.array_size * sizeof(float));
    float *to_buffer = (float *)malloc(ARRAY_TEST_SIZE *
                                       driver->arch.array_size * sizeof(float));
    float *weights_buffer =
        (float *)malloc(driver->arch.array_size * sizeof(float));

    if (!from_buffer || !to_buffer || !weights_buffer) {
        error =
            DRIVER_ERROR(ERROR_DRIVER_OUT_OF_HEAP_MEMORY, "Out of heap memory");
        goto cleanup;
    }

    write_dram_random_vectors(driver, DRAM0, ARRAY_TEST_INPUT_DRAM0_ADDRESS, 0,
                              ARRAY_TEST_SIZE);
    driver_read_dram_vectors(driver, DRAM0, ARRAY_TEST_INPUT_DRAM0_ADDRESS, 0,
                             ARRAY_TEST_SIZE, from_buffer);

    for (size_t j = 0; j < driver->arch.array_size; j++)
        weights_buffer[j] = ARRAY_TEST_BIAS;

    // TODO: Use non-identity weights to test all MAC units

    driver_write_dram_vectors(driver, DRAM1, ARRAY_TEST_WEIGHTS_DRAM1_ADDRESS,
                              0, 1, weights_buffer);

    for (size_t i = 0; i < driver->arch.array_size; i++) {
        for (size_t j = 0; j < driver->arch.array_size; j++)
            if (i == j)
                weights_buffer[j] = ARRAY_TEST_IDENTITY_WEIGHT;
            else
                weights_buffer[j] = 0.0;

        driver_write_dram_vectors(driver, DRAM1,
                                  ARRAY_TEST_WEIGHTS_DRAM1_ADDRESS + 1 + i, 0,
                                  1, weights_buffer);
    }

    error = setup_buffer_preamble(driver);

    if (error)
        goto cleanup;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_DRAM1_TO_LOCAL, ARRAY_TEST_WEIGHTS_LOCAL_ADDRESS,
        ARRAY_TEST_WEIGHTS_DRAM1_ADDRESS, driver->arch.array_size);

    if (error)
        goto cleanup;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_LOAD_WEIGHT, 0,
        ARRAY_TEST_WEIGHTS_LOCAL_ADDRESS, driver->arch.array_size, 0);

    if (error)
        goto cleanup;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_DRAM0_TO_LOCAL, ARRAY_TEST_INPUT_LOCAL_ADDRESS,
        ARRAY_TEST_INPUT_DRAM0_ADDRESS, ARRAY_TEST_SIZE - 1);

    if (error)
        goto cleanup;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_MAT_MUL, 0,
        ARRAY_TEST_INPUT_LOCAL_ADDRESS, ARRAY_TEST_OUTPUT_ACC_ADDRESS,
        ARRAY_TEST_SIZE - 1);

    if (error)
        goto cleanup;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_ACC_TO_LOCAL, ARRAY_TEST_OUTPUT_LOCAL_ADDRESS,
        ARRAY_TEST_OUTPUT_ACC_ADDRESS, ARRAY_TEST_SIZE - 1);

    if (error)
        goto cleanup;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_LOCAL_TO_DRAM0, ARRAY_TEST_OUTPUT_LOCAL_ADDRESS,
        ARRAY_TEST_OUTPUT_DRAM0_ADDRESS, ARRAY_TEST_SIZE - 1);

    if (error)
        goto cleanup;

    error = setup_buffer_postamble(driver);

    if (error)
        goto cleanup;

    error = driver_run(driver, true, true, true, true);

    if (error)
        goto cleanup;

    driver_read_dram_vectors(driver, DRAM0, ARRAY_TEST_OUTPUT_DRAM0_ADDRESS, 0,
                             ARRAY_TEST_SIZE, to_buffer);

    for (size_t k = 0; k < ARRAY_TEST_SIZE * driver->arch.array_size; k++) {
        from_buffer[k] =
            saturate(driver->arch.data_type,
                     saturate(driver->arch.data_type,
                              from_buffer[k] * ARRAY_TEST_IDENTITY_WEIGHT) +
                         ARRAY_TEST_BIAS);

        if (compare_scalars(driver->arch.data_type, from_buffer[k],
                            to_buffer[k])) {
            bad_indexes[bad_indexes_size++] = k;

            if (bad_indexes_size == TEST_MAX_BAD_INDEXES_SIZE)
                break;
        }
    }

    printf("%s\n", bad_indexes_size ? failed : ok);

    if (bad_indexes_size && verbose)
        for (size_t k = 0; k < bad_indexes_size; k++) {
            size_t bad_index = bad_indexes[k];

            printf("\t at %zu expected=%f, actual=%f\n", bad_index,
                   from_buffer[bad_index], to_buffer[bad_index]);
        }

cleanup:
    free(from_buffer);
    free(to_buffer);
    free(weights_buffer);

    return error;
}

#define SIMD_TEST_SIZE (driver->arch.accumulator_depth / 4)

#define SIMD_TEST_MUL 3.456
#define SIMD_TEST_ADD 78.912

#define SIMD_TEST_INPUT_DRAM0_ADDRESS 0
#define SIMD_TEST_INPUT_ACC_ADDRESS 0
#define SIMD_TEST_INPUT_LOCAL_ADDRESS 0

#define SIMD_TEST_MULS_DRAM1_ADDRESS 0
#define SIMD_TEST_MULS_ACC_ADDRESS SIMD_TEST_SIZE
#define SIMD_TEST_MULS_LOCAL_ADDRESS SIMD_TEST_SIZE

#define SIMD_TEST_ADDS_DRAM1_ADDRESS SIMD_TEST_SIZE
#define SIMD_TEST_ADDS_ACC_ADDRESS (SIMD_TEST_SIZE * 2)
#define SIMD_TEST_ADDS_LOCAL_ADDRESS (SIMD_TEST_SIZE * 2)

#define SIMD_TEST_OUTPUT_ACC_ADDRESS (SIMD_TEST_SIZE * 3)
#define SIMD_TEST_OUTPUT_LOCAL_ADDRESS (SIMD_TEST_SIZE * 3)
#define SIMD_TEST_OUTPUT_DRAM0_ADDRESS SIMD_TEST_SIZE

error_t driver_run_simd_test(struct driver *driver, bool verbose) {
    error_t error = ERROR_NONE;
    size_t bad_indexes[TEST_MAX_BAD_INDEXES_SIZE];
    size_t bad_indexes_size = 0;

    float *from_buffer = (float *)malloc(
        SIMD_TEST_SIZE * driver->arch.array_size * sizeof(float));
    float *to_buffer = (float *)malloc(SIMD_TEST_SIZE *
                                       driver->arch.array_size * sizeof(float));
    float *consts_buffer =
        (float *)malloc(driver->arch.array_size * sizeof(float));

    if (!from_buffer || !to_buffer || !consts_buffer) {
        error =
            DRIVER_ERROR(ERROR_DRIVER_OUT_OF_HEAP_MEMORY, "Out of heap memory");
        goto cleanup;
    }

    write_dram_random_vectors(driver, DRAM0, SIMD_TEST_INPUT_DRAM0_ADDRESS, 0,
                              SIMD_TEST_SIZE);
    driver_read_dram_vectors(driver, DRAM0, SIMD_TEST_INPUT_DRAM0_ADDRESS, 0,
                             SIMD_TEST_SIZE, from_buffer);

    for (size_t j = 0; j < driver->arch.array_size; j++)
        consts_buffer[j] = SIMD_TEST_MUL;

    for (size_t i = 0; i < SIMD_TEST_SIZE; i++) {
        driver_write_dram_vectors(driver, DRAM1,
                                  SIMD_TEST_MULS_DRAM1_ADDRESS + i, 0, 1,
                                  consts_buffer);
    }

    for (size_t j = 0; j < driver->arch.array_size; j++)
        consts_buffer[j] = SIMD_TEST_ADD;

    for (size_t i = 0; i < SIMD_TEST_SIZE; i++) {
        driver_write_dram_vectors(driver, DRAM1,
                                  SIMD_TEST_ADDS_DRAM1_ADDRESS + i, 0, 1,
                                  consts_buffer);
    }

    error = setup_buffer_preamble(driver);

    if (error)
        goto cleanup;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_DRAM1_TO_LOCAL, SIMD_TEST_MULS_LOCAL_ADDRESS,
        SIMD_TEST_MULS_DRAM1_ADDRESS, SIMD_TEST_SIZE - 1);

    if (error)
        goto cleanup;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_DRAM1_TO_LOCAL, SIMD_TEST_ADDS_LOCAL_ADDRESS,
        SIMD_TEST_ADDS_DRAM1_ADDRESS, SIMD_TEST_SIZE - 1);

    if (error)
        goto cleanup;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_LOCAL_TO_ACC, SIMD_TEST_MULS_LOCAL_ADDRESS,
        SIMD_TEST_MULS_ACC_ADDRESS, SIMD_TEST_SIZE - 1);

    if (error)
        goto cleanup;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_LOCAL_TO_ACC, SIMD_TEST_ADDS_LOCAL_ADDRESS,
        SIMD_TEST_ADDS_ACC_ADDRESS, SIMD_TEST_SIZE - 1);

    if (error)
        goto cleanup;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_DRAM0_TO_LOCAL, SIMD_TEST_INPUT_LOCAL_ADDRESS,
        SIMD_TEST_INPUT_DRAM0_ADDRESS, SIMD_TEST_SIZE - 1);

    if (error)
        goto cleanup;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_LOCAL_TO_ACC, SIMD_TEST_INPUT_LOCAL_ADDRESS,
        SIMD_TEST_INPUT_ACC_ADDRESS, SIMD_TEST_SIZE - 1);

    if (error)
        goto cleanup;

    for (size_t i = 0; i < SIMD_TEST_SIZE; i++) {
        // TODO: need to respect SIMD op layout from arch
        // TODO: need to test SIMD ops other than Move, Multiply and Add
        // TODO: need to specialize the test when >1 SIMD registers are
        // available

        error = buffer_append_instruction(
            &driver->buffer, &driver->layout, OPCODE_SIMD, SIMD_FLAG_READ, 0,
            SIMD_TEST_INPUT_ACC_ADDRESS + i, (SIMD_OPCODE_MOVE << 3) | 0b001);

        if (error)
            goto cleanup;

        error = buffer_append_instruction(
            &driver->buffer, &driver->layout, OPCODE_SIMD, SIMD_FLAG_READ, 0,
            SIMD_TEST_MULS_ACC_ADDRESS + i, (SIMD_OPCODE_MUL << 3) | 0b101);

        if (error)
            goto cleanup;

        error = buffer_append_instruction(
            &driver->buffer, &driver->layout, OPCODE_SIMD,
            SIMD_FLAG_READ | SIMD_FLAG_WRITE, SIMD_TEST_OUTPUT_ACC_ADDRESS + i,
            SIMD_TEST_ADDS_ACC_ADDRESS + i, (SIMD_OPCODE_ADD << 3) | 0b100);

        if (error)
            goto cleanup;
    }

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_ACC_TO_LOCAL, SIMD_TEST_OUTPUT_LOCAL_ADDRESS,
        SIMD_TEST_OUTPUT_ACC_ADDRESS, SIMD_TEST_SIZE - 1);

    if (error)
        goto cleanup;

    error = buffer_append_instruction(
        &driver->buffer, &driver->layout, OPCODE_DATA_MOVE,
        DATA_MOVE_FLAG_LOCAL_TO_DRAM0, SIMD_TEST_OUTPUT_LOCAL_ADDRESS,
        SIMD_TEST_OUTPUT_DRAM0_ADDRESS, SIMD_TEST_SIZE - 1);

    if (error)
        goto cleanup;

    error = setup_buffer_postamble(driver);

    if (error)
        goto cleanup;

    error = driver_run(driver, false, false, false, false);

    if (error)
        goto cleanup;

    driver_read_dram_vectors(driver, DRAM0, SIMD_TEST_OUTPUT_DRAM0_ADDRESS, 0,
                             SIMD_TEST_SIZE, to_buffer);

    for (size_t k = 0; k < SIMD_TEST_SIZE * driver->arch.array_size; k++) {
        from_buffer[k] = saturate(
            driver->arch.data_type,
            saturate(driver->arch.data_type, from_buffer[k] * SIMD_TEST_MUL) +
                SIMD_TEST_ADD);

        if (compare_scalars(driver->arch.data_type, from_buffer[k],
                            to_buffer[k])) {
            bad_indexes[bad_indexes_size++] = k;

            if (bad_indexes_size == TEST_MAX_BAD_INDEXES_SIZE)
                break;
        }
    }

    printf("%s\n", bad_indexes_size ? failed : ok);

    if (bad_indexes_size && verbose)
        for (size_t k = 0; k < bad_indexes_size; k++) {
            size_t bad_index = bad_indexes[k];

            printf("\t at %zu expected=%f, actual=%f\n", bad_index,
                   from_buffer[bad_index], to_buffer[bad_index]);
        }

cleanup:
    free(from_buffer);
    free(to_buffer);
    free(consts_buffer);

    return error;
}

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID

#define SAMPLING_TEST_SIZE (64 * 1024 * 1024)

error_t driver_run_sampling_test(struct driver *driver, bool verbose) {
    error_t error = setup_buffer_preamble(driver);

    if (error)
        return error;

    error = buffer_append_noop_instructions(&driver->buffer, &driver->layout,
                                            SAMPLING_TEST_SIZE);

    if (error)
        return error;

    error = setup_buffer_postamble(driver);

    if (error)
        return error;

    error = driver_run(driver, false, false, false, false);

    if (error)
        return error;

    sample_buffer_before_read(&driver->sample_buffer);

    size_t samples_count = driver->sample_buffer.offset / SAMPLE_SIZE_BYTES;
    size_t valid_samples_count = 0;
    size_t stalling_samples_count = 0;
    size_t missing_samples_count = 0;

    uint32_t prev_instruction_counter = 0;

    for (size_t i = 0; i < samples_count; i++) {
        uint8_t *sample_ptr =
            driver->sample_buffer.ptr + (i * SAMPLE_SIZE_BYTES);
        uint32_t instruction_counter = *((uint32_t *)sample_ptr);
        uint32_t instruction_offset =
            instruction_counter * driver->layout.instruction_size_bytes;

        if (instruction_counter != UINT32_MAX &&
            instruction_offset < driver->buffer.offset) {
            valid_samples_count++;

            if (!prev_instruction_counter) {
                prev_instruction_counter = instruction_counter;
            } else {
                if (prev_instruction_counter == instruction_counter)
                    stalling_samples_count++;
                else {
                    if (instruction_counter >
                        prev_instruction_counter + SAMPLE_INTERVAL_CYCLES) {
                        if (verbose)
                            printf("Offset %u -> %u\n",
                                   (unsigned int)prev_instruction_counter,
                                   (unsigned int)instruction_counter);

                        missing_samples_count++;
                    }
                    prev_instruction_counter = instruction_counter;
                }
            }
        }
    }

    printf("%s: collected %lu samples, %lu valid, %lu stalling",
           missing_samples_count ? failed : ok, samples_count,
           valid_samples_count, stalling_samples_count);

    if (missing_samples_count)
        printf(", %lu missing\n", missing_samples_count);
    else
        printf("\n");

    return ERROR_NONE;
}

#endif
