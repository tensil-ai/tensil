/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "architecture.h"
#include "instruction.h"
#include "instruction_buffer.h"
#include "platform.h"
#include "sample_buffer.h"
#include "tcu.h"

enum tensil_dram_bank { TENSIL_DRAM0 = 0, TENSIL_DRAM1 = 1 };

struct tensil_driver {
    struct tensil_architecture arch;

#ifdef TENSIL_PLATFORM_DECODER_TIMEOUT
    uint16_t decoder_timeout;
#endif

    uint8_t *dram0_base_ptr;
    uint8_t *dram1_base_ptr;

    size_t dram0_size;
    size_t dram1_size;

    struct tensil_compute_unit tcu;
    struct tensil_instruction_buffer buffer;
    struct tensil_instruction_layout layout;

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    size_t sample_block_size;
    struct tensil_sample_buffer sample_buffer;
#endif
};

struct tensil_model;

tensil_error_t tensil_driver_init(struct tensil_driver *driver);

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

tensil_error_t
tensil_driver_load_program_from_file(struct tensil_driver *driver, size_t size,
                                     const char *file_name);

tensil_error_t tensil_driver_load_dram_vectors_from_file(
    struct tensil_driver *driver, enum tensil_dram_bank dram_bank,
    size_t offset, size_t size, const char *file_name);

tensil_error_t tensil_driver_load_model_input_from_file(
    struct tensil_driver *driver, const struct tensil_model *model,
    const char *input_name, const char *file_name);

tensil_error_t tensil_driver_load_model(struct tensil_driver *driver,
                                        const struct tensil_model *model);

#endif

tensil_error_t tensil_driver_load_model_input_scalars(
    struct tensil_driver *driver, const struct tensil_model *model,
    const char *input_name, size_t size, const float *buffer);

tensil_error_t tensil_driver_load_model_input_vector_scalars(
    struct tensil_driver *driver, const struct tensil_model *model,
    const char *input_name, size_t vector_offset, size_t scalars_size,
    const float *buffer);

tensil_error_t tensil_driver_get_model_output_scalars(
    const struct tensil_driver *driver, const struct tensil_model *model,
    const char *output_name, size_t size, float *buffer);

#ifdef TENSIL_PLATFORM_ENABLE_STDIO

tensil_error_t
tensil_driver_print_model_output_vectors(const struct tensil_driver *driver,
                                         const struct tensil_model *model,
                                         const char *output_name);

#endif

tensil_error_t tensil_driver_write_dram_vectors(struct tensil_driver *driver,
                                                enum tensil_dram_bank dram_bank,
                                                size_t offset, size_t stride,
                                                size_t size, float *buffer);

tensil_error_t
tensil_driver_read_dram_vectors(const struct tensil_driver *driver,
                                enum tensil_dram_bank dram_bank, size_t offset,
                                size_t stride, size_t size, float *buffer);

struct tensil_run_opts {
#ifdef TENSIL_PLATFORM_ENABLE_STDIO
    bool print_sampling_summary;
    bool print_sampling_aggregates;
    bool print_sampling_listing;
#endif

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
    const char *sample_file_name;
#endif
};

tensil_error_t tensil_driver_run(struct tensil_driver *driver,
                                 const struct tensil_run_opts *run_opts);

#ifdef TENSIL_PLATFORM_ENABLE_STDIO

tensil_error_t tensil_driver_run_memory_test(struct tensil_driver *driver,
                                             enum tensil_dram_bank from_bank,
                                             enum tensil_dram_bank to_bank,
                                             bool verbose);

tensil_error_t tensil_driver_run_array_test(struct tensil_driver *driver,
                                            bool verbose);

tensil_error_t tensil_driver_run_simd_test(struct tensil_driver *driver,
                                           bool verbose);

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID

tensil_error_t tensil_driver_run_sampling_test(struct tensil_driver *driver,
                                               bool verbose);

#endif

#endif

// Internal functions

uint8_t *
tensil_driver_get_dram_bank_base_ptr(const struct tensil_driver *driver,
                                     enum tensil_dram_bank dram_bank);

tensil_error_t
tensil_driver_setup_buffer_postamble(struct tensil_driver *driver);

tensil_error_t
tensil_driver_setup_buffer_preamble(struct tensil_driver *driver);
