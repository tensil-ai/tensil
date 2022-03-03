/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#include "sample_buffer.h"

#ifdef TENSIL_PLATFORM_SAMPLE_AXI_DMA_DEVICE_ID

#include <malloc.h>
#include <stdio.h>
#include <string.h>

#include "xil_cache.h"

#include "instruction.h"
#include "instruction_buffer.h"

#define HEADER_COUNTS_SIZE (1 << 8)
#define OPCODE_COUNTS_SIZE (1 << 4)
#define FLAGS_COUNTS_SIZE (1 << 16)

typedef unsigned int counter_t;

void sample_buffer_reset(struct sample_buffer *sample_buffer) {
    sample_buffer->offset = 0;
}

void sample_buffer_before_read(const struct sample_buffer *sample_buffer) {
    Xil_DCacheFlushRange((UINTPTR)sample_buffer->ptr, sample_buffer->offset);
}

static void print_flags(uint16_t flags) {
    for (size_t k = 0; k < 16; k++) {
        switch (k) {
        case 0:
            printf("Array=");
            break;
        case 2:
            printf(", Acc=");
            break;
        case 4:
            printf(", Dataflow=");
            break;
        case 6:
            printf(", DRAM1=");
            break;
        case 8:
            printf(", DRAM0=");
            break;
        case 10:
            printf(", MemPortB=");
            break;
        case 12:
            printf(", MemPortA=");
            break;
        case 14:
            printf(", Instruction=");
            break;
        default:
            break;
        }

        if (flags & 1)
            printf("1");
        else
            printf("0");

        flags >>= 1;
    }
}

static void print_flags_counts(const counter_t *flags_counts) {
    printf("Array=VR, Acc=VR, Dataflow=VR, DRAM1=VR, DRAM0=VR, MemPortB=VR, "
           "MemPortA=VR, Instruction=VR\n");
    for (size_t i = 0; i < FLAGS_COUNTS_SIZE; i++) {
        counter_t count = flags_counts[i];
        if (count) {
            print_flags(i);
            printf(": %u\n", count);
        }
    }
}

static const char *opcode_to_string(uint8_t opcode) {
    switch (opcode) {
    case OPCODE_NOOP:
        return "NoOp";
    case OPCODE_MAT_MUL:
        return "MatMul";
    case OPCODE_DATA_MOVE:
        return "DataMove";
    case OPCODE_LOAD_WEIGHT:
        return "LoadWeight";
    case OPCODE_SIMD:
        return "SIMD";
    case OPCODE_CONFIG:
        return "Config";
    default:
        return "???";
    }
}

error_t sample_buffer_print_analysis(
    const struct sample_buffer *sample_buffer,
    const struct instruction_buffer *instruction_buffer,
    const struct instruction_layout *layout, bool print_summary,
    bool print_aggregates, bool print_listing, uint32_t program_counter_shift) {
    size_t samples_count = sample_buffer->offset / SAMPLE_SIZE_BYTES;
    size_t valid_samples_count = 0;

    counter_t header_counts[HEADER_COUNTS_SIZE];

    counter_t opcode_counts[OPCODE_COUNTS_SIZE];

    memset(header_counts, 0, HEADER_COUNTS_SIZE * sizeof(counter_t));

    memset(opcode_counts, 0, OPCODE_COUNTS_SIZE * sizeof(counter_t));

    counter_t *matmul_flags_counts =
        (counter_t *)malloc(FLAGS_COUNTS_SIZE * sizeof(counter_t));
    counter_t *data_move_flags_counts =
        (counter_t *)malloc(FLAGS_COUNTS_SIZE * sizeof(counter_t));
    counter_t *load_weight_flags_counts =
        (counter_t *)malloc(FLAGS_COUNTS_SIZE * sizeof(counter_t));
    counter_t *simd_flags_counts =
        (counter_t *)malloc(FLAGS_COUNTS_SIZE * sizeof(counter_t));
    counter_t *noop_flags_counts =
        (counter_t *)malloc(FLAGS_COUNTS_SIZE * sizeof(counter_t));

    if (!matmul_flags_counts || !data_move_flags_counts ||
        !load_weight_flags_counts || !simd_flags_counts || !noop_flags_counts)
        return DRIVER_ERROR(ERROR_DRIVER_OUT_OF_HEAP_MEMORY,
                            "Out of heap memory");

    memset(matmul_flags_counts, 0, FLAGS_COUNTS_SIZE * sizeof(counter_t));
    memset(data_move_flags_counts, 0, FLAGS_COUNTS_SIZE * sizeof(counter_t));
    memset(load_weight_flags_counts, 0, FLAGS_COUNTS_SIZE * sizeof(counter_t));
    memset(simd_flags_counts, 0, FLAGS_COUNTS_SIZE * sizeof(counter_t));
    memset(noop_flags_counts, 0, FLAGS_COUNTS_SIZE * sizeof(counter_t));

    printf("Collected %zu samples\n", samples_count);

    for (size_t i = 0; i < samples_count; i++) {
        uint8_t *sample_ptr = sample_buffer->ptr + (i * SAMPLE_SIZE_BYTES);
        uint32_t program_counter = *((uint32_t *)sample_ptr);
        uint32_t instruction_offset =
            program_counter * layout->instruction_size_bytes;
        uint16_t flags = *((uint16_t *)(sample_ptr + 4));

        if (program_counter != UINT32_MAX &&
            instruction_offset < instruction_buffer->offset) {
            valid_samples_count++;

            uint8_t *instruction_ptr =
                instruction_buffer->ptr + instruction_offset;
            uint8_t header =
                instruction_ptr[layout->instruction_size_bytes - 1];
            uint8_t opcode = header >> 4;

            header_counts[header]++;
            opcode_counts[opcode]++;

            switch (opcode) {
            case OPCODE_MAT_MUL:
                matmul_flags_counts[flags]++;
                break;
            case OPCODE_DATA_MOVE:
                data_move_flags_counts[flags]++;
                break;
            case OPCODE_LOAD_WEIGHT:
                load_weight_flags_counts[flags]++;
                break;
            case OPCODE_SIMD:
                simd_flags_counts[flags]++;
                break;
            case OPCODE_NOOP:
                noop_flags_counts[flags]++;
                break;
            default:
                break;
            }

            if (print_listing) {
                printf("[%08u] %s: ",
                       (unsigned int)program_counter - program_counter_shift,
                       opcode_to_string(opcode));
                print_flags(flags);
                printf("\n");
            }
        }
    }

    printf("Found %zu valid samples\n", valid_samples_count);

    if (print_summary) {
        printf("Samples per opcode ---------------------------------------\n");
        printf("NoOp:       %u\n", opcode_counts[OPCODE_NOOP]);
        printf("MatMul:     %u\n", opcode_counts[OPCODE_MAT_MUL]);
        printf("DataMove:   %u\n", opcode_counts[OPCODE_DATA_MOVE]);
        printf("LoadWeight: %u\n", opcode_counts[OPCODE_LOAD_WEIGHT]);
        printf("SIMD:       %u\n", opcode_counts[OPCODE_SIMD]);

        printf("Samples per DataMove flag "
               "---------------------------------------\n");
        printf("DRAM0->Local:            %u\n",
               header_counts[OPCODE_DATA_MOVE << 4 |
                             DATA_MOVE_FLAG_DRAM0_TO_LOCAL]);
        printf("Local->DRAM0:            %u\n",
               header_counts[OPCODE_DATA_MOVE << 4 |
                             DATA_MOVE_FLAG_LOCAL_TO_DRAM0]);
        printf("DRAM1->Local:            %u\n",
               header_counts[OPCODE_DATA_MOVE << 4 |
                             DATA_MOVE_FLAG_DRAM1_TO_LOCAL]);
        printf("Local->DRAM1:            %u\n",
               header_counts[OPCODE_DATA_MOVE << 4 |
                             DATA_MOVE_FLAG_LOCAL_TO_DRAM1]);
        printf(
            "Accumulator->Local:      %u\n",
            header_counts[OPCODE_DATA_MOVE << 4 | DATA_MOVE_FLAG_ACC_TO_LOCAL]);
        printf(
            "Local->Accumulator:      %u\n",
            header_counts[OPCODE_DATA_MOVE << 4 | DATA_MOVE_FLAG_LOCAL_TO_ACC]);
        printf("Local->Accumulator(Acc): %u\n",
               header_counts[OPCODE_DATA_MOVE << 4 |
                             DATA_MOVE_FLAG_LOCAL_TO_ACC_WITH_ACC]);
    }

    if (print_aggregates) {
        printf("MatMul flags ---------------------------------------\n");
        print_flags_counts(matmul_flags_counts);
        printf("DataMove flags ---------------------------------------\n");
        print_flags_counts(data_move_flags_counts);
        printf("LoadWeight flags ---------------------------------------\n");
        print_flags_counts(load_weight_flags_counts);
        printf("SIMD flags ---------------------------------------\n");
        print_flags_counts(simd_flags_counts);
        printf("NoOp flags ---------------------------------------\n");
        print_flags_counts(noop_flags_counts);
    }

    free(matmul_flags_counts);
    free(data_move_flags_counts);
    free(load_weight_flags_counts);
    free(simd_flags_counts);
    free(noop_flags_counts);

    return ERROR_NONE;
}

#endif
