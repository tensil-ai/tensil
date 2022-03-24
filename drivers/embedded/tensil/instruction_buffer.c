/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#include "instruction_buffer.h"

#include <string.h>

#include "xil_cache.h"
#include "xstatus.h"

#include "instruction.h"

#ifdef TENSIL_PLATFORM_ENABLE_FATFS
#include "fatfs.h"
#endif

static error_t
move_to_next_instruction(struct instruction_buffer *buffer,
                         const struct instruction_layout *layout) {
    if (layout->instruction_size_bytes > buffer->size - buffer->offset)
        return DRIVER_ERROR(ERROR_DRIVER_INSUFFICIENT_BUFFER,
                            "Program is too big");

    buffer->offset += layout->instruction_size_bytes;

    return ERROR_NONE;
}

error_t buffer_append_instruction(struct instruction_buffer *buffer,
                                  const struct instruction_layout *layout,
                                  uint8_t opcode, uint8_t flags,
                                  uint64_t operand0, uint64_t operand1,
                                  uint64_t operand2) {
    size_t curr_offset = buffer->offset;
    error_t error = move_to_next_instruction(buffer, layout);

    if (error)
        return error;

    instruction_set(layout, buffer->ptr, curr_offset, opcode, flags, operand0,
                    operand1, operand2);

    Xil_DCacheFlushRange((UINTPTR)buffer->ptr + curr_offset,
                         layout->instruction_size_bytes);

    return ERROR_NONE;
}

error_t
buffer_append_config_instruction(struct instruction_buffer *buffer,
                                 const struct instruction_layout *layout,
                                 uint8_t reg, uint64_t value) {
    size_t curr_offset = buffer->offset;
    error_t error = move_to_next_instruction(buffer, layout);

    if (error)
        return error;

    // BUG: need to update documentation for config opcode to have special
    // operand alignment
    instruction_set_all(layout, buffer->ptr, curr_offset, OPCODE_CONFIG, 0,
                        (value << 4) | reg);

    Xil_DCacheFlushRange((UINTPTR)buffer->ptr + curr_offset,
                         layout->instruction_size_bytes);

    return ERROR_NONE;
}

error_t buffer_append_noop_instructions(struct instruction_buffer *buffer,
                                        const struct instruction_layout *layout,
                                        size_t count) {
    size_t size = count * layout->instruction_size_bytes;
    if (size > buffer->size - buffer->offset)
        return DRIVER_ERROR(ERROR_DRIVER_INSUFFICIENT_BUFFER,
                            "Program is too big");

    memset(buffer->ptr + buffer->offset, 0, size);

    Xil_DCacheFlushRange((UINTPTR)buffer->ptr + buffer->offset, size);

    buffer->offset += size;

    return ERROR_NONE;
}

error_t buffer_append_program(struct instruction_buffer *buffer,
                              const uint8_t *ptr, size_t size) {
    if (size > buffer->size - buffer->offset)
        return DRIVER_ERROR(ERROR_DRIVER_INSUFFICIENT_BUFFER,
                            "Program is too big");

    memcpy(buffer->ptr + buffer->offset, ptr, size);

    Xil_DCacheFlushRange((UINTPTR)buffer->ptr + buffer->offset, size);

    buffer->offset += size;

    return ERROR_NONE;
}

#ifdef TENSIL_PLATFORM_FLASH_READ

error_t buffer_append_program_from_flash(struct instruction_buffer *buffer,
                                         size_t size,
                                         TENSIL_PLATFORM_FLASH_TYPE flash) {
    if (size > buffer->size - buffer->offset)
        return DRIVER_ERROR(ERROR_DRIVER_INSUFFICIENT_BUFFER,
                            "Program is too big");

    while (size) {
        size_t flash_read_size = 0;
        int status = TENSIL_PLATFORM_FLASH_READ(
            flash, buffer->ptr + buffer->offset, size, &flash_read_size);

        if (status != XST_SUCCESS)
            return XILINX_ERROR(status);

        size -= flash_read_size;
        buffer->offset += flash_read_size;
    }

    return ERROR_NONE;
}

#endif

#ifdef TENSIL_PLATFORM_ENABLE_FATFS

error_t buffer_append_program_from_file(struct instruction_buffer *buffer,
                                        size_t size, const char *file_name) {
    FIL fil;
    FILINFO fno;
    FRESULT res;
    FATFS_UINT bytes_read;

    memset(&fno, 0, sizeof(FILINFO));
    res = f_stat(file_name, &fno);
    if (res)
        return FS_ERROR(res);

    if (fno.fsize != size)
        return DRIVER_ERROR(ERROR_DRIVER_UNEXPECTED_PROGRAM_SIZE,
                            "Unexpected program size in %s", file_name);

    if (fno.fsize > buffer->size - buffer->offset)
        return DRIVER_ERROR(ERROR_DRIVER_INSUFFICIENT_BUFFER,
                            "Program is too big in %s", file_name);

    memset(&fil, 0, sizeof(FIL));
    res = f_open(&fil, file_name, FA_READ);
    if (res)
        return FS_ERROR(res);

    res = f_read(&fil, (void *)(buffer->ptr + buffer->offset), fno.fsize,
                 &bytes_read);

    f_close(&fil);

    if (res)
        return FS_ERROR(res);

    Xil_DCacheFlushRange((UINTPTR)buffer->ptr + buffer->offset, fno.fsize);

    buffer->offset += fno.fsize;

    return ERROR_NONE;
}

#endif

error_t buffer_pad_to_alignment(struct instruction_buffer *buffer,
                                const struct instruction_layout *layout,
                                int alignment_bytes) {
    while (buffer->offset & (alignment_bytes - 1)) {
        error_t error =
            buffer_append_instruction(buffer, layout, OPCODE_NOOP, 0, 0, 0, 0);

        if (error)
            return error;
    }
    return ERROR_NONE;
}

void buffer_reset(struct instruction_buffer *buffer) { buffer->offset = 0; }
