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

enum dram_bank { DRAM0 = 0, DRAM1 = 1 };

struct driver {
    struct architecture arch;

    uint16_t decoder_timeout;

    uint8_t *dram0_base_ptr;
    uint8_t *dram1_base_ptr;

    size_t dram0_size;
    size_t dram1_size;

    struct tcu tcu;
    struct instruction_buffer buffer;
    struct instruction_layout layout;

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID
    size_t sample_block_size;
    struct sample_buffer sample_buffer;
#endif
};

struct model;

error_t driver_init(struct driver *driver);

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

error_t driver_load_program_from_file(struct driver *driver, size_t size,
                                      const char *file_name);

error_t driver_load_dram_vectors_from_file(struct driver *driver,
                                           enum dram_bank dram_bank,
                                           size_t offset, size_t size,
                                           const char *file_name);

error_t driver_load_model_input_from_file(struct driver *driver,
                                          const struct model *model,
                                          const char *input_name,
                                          const char *file_name);

error_t driver_load_model(struct driver *driver, const struct model *model);

#endif

#ifdef TENSIL_PLATFORM_FLASH_READ

error_t driver_load_dram_vectors_from_flash(struct driver *driver,
                                            enum dram_bank dram_bank,
                                            size_t offset, size_t size,
                                            TENSIL_PLATFORM_FLASH_TYPE flash);

error_t driver_load_model_from_flash(struct driver *driver,
                                     const struct model *model,
                                     TENSIL_PLATFORM_FLASH_TYPE flash);

error_t driver_load_program_from_flash(struct driver *driver, size_t size,
                                       TENSIL_PLATFORM_FLASH_TYPE flash);

error_t driver_load_model_input_from_flash(struct driver *driver,
                                           const struct model *model,
                                           const char *input_name,
                                           TENSIL_PLATFORM_FLASH_TYPE flash);

#endif

error_t driver_load_model_input_scalars(struct driver *driver,
                                        const struct model *model,
                                        const char *input_name, size_t size,
                                        const float *buffer);

error_t driver_load_model_input_vector_scalars(
    struct driver *driver, const struct model *model, const char *input_name,
    size_t vector_offset, size_t scalars_size, const float *buffer);

error_t driver_get_model_output_scalars(const struct driver *driver,
                                        const struct model *model,
                                        const char *output_name, size_t size,
                                        float *buffer);

#ifdef TENSIL_PLATFORM_ENABLE_PRINTF

error_t driver_print_model_output_vectors(const struct driver *driver,
                                          const struct model *model,
                                          const char *output_name);

#endif

error_t driver_write_dram_vectors(struct driver *driver,
                                  enum dram_bank dram_bank, size_t offset,
                                  size_t stride, size_t size, float *buffer);

error_t driver_read_dram_vectors(const struct driver *driver,
                                 enum dram_bank dram_bank, size_t offset,
                                 size_t stride, size_t size, float *buffer);

struct run_opts {
#ifdef TENSIL_PLATFORM_ENABLE_PRINTF
    bool print_timing;
    bool print_sampling_summary;
    bool print_sampling_aggregates;
    bool print_sampling_listing;
#endif

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
    const char *sample_file_name;
#endif
};

error_t driver_run(struct driver *driver, const struct run_opts *run_opts);

#ifdef TENSIL_PLATFORM_ENABLE_PRINTF

error_t driver_run_memory_test(struct driver *driver, enum dram_bank from_bank,
                               enum dram_bank to_bank, bool verbose);

error_t driver_run_array_test(struct driver *driver, bool verbose);

error_t driver_run_simd_test(struct driver *driver, bool verbose);

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID

error_t driver_run_sampling_test(struct driver *driver, bool verbose);

#endif

#endif

// Internal functions

uint8_t *driver_get_dram_bank_base_ptr(const struct driver *driver,
                                       enum dram_bank dram_bank);

error_t driver_setup_buffer_postamble(struct driver *driver);

error_t driver_setup_buffer_preamble(struct driver *driver);