/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#include "instruction_buffer.h"

#include <string.h>

#include "xil_cache.h"
#include "xstatus.h"

#include "instruction.h"

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM
#include "ff.h"
#endif

static tensil_error_t
move_to_next_instruction(struct tensil_instruction_buffer *buffer,
                         const struct tensil_instruction_layout *layout) {
    if (layout->instruction_size_bytes > buffer->size - buffer->offset)
        return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_INSUFFICIENT_BUFFER,
                                   "Program is too big");

    buffer->offset += layout->instruction_size_bytes;

    return TENSIL_ERROR_NONE;
}

tensil_error_t tensil_buffer_append_instruction(
    struct tensil_instruction_buffer *buffer,
    const struct tensil_instruction_layout *layout, uint8_t opcode,
    uint8_t flags, uint64_t operand0, uint64_t operand1, uint64_t operand2) {
    size_t curr_offset = buffer->offset;
    tensil_error_t error = move_to_next_instruction(buffer, layout);

    if (error)
        return error;

    tensil_instruction_set(layout, buffer->ptr, curr_offset, opcode, flags,
                           operand0, operand1, operand2);

    Xil_DCacheFlushRange((UINTPTR)buffer->ptr + curr_offset,
                         layout->instruction_size_bytes);

    return TENSIL_ERROR_NONE;
}

tensil_error_t tensil_buffer_append_config_instruction(
    struct tensil_instruction_buffer *buffer,
    const struct tensil_instruction_layout *layout, uint8_t reg,
    uint64_t value) {
    size_t curr_offset = buffer->offset;
    tensil_error_t error = move_to_next_instruction(buffer, layout);

    if (error)
        return error;

    // BUG: need to update documentation for config opcode to have special
    // operand alignment
    tensil_instruction_set_all(layout, buffer->ptr, curr_offset,
                               TENSIL_OPCODE_CONFIG, 0, (value << 4) | reg);

    Xil_DCacheFlushRange((UINTPTR)buffer->ptr + curr_offset,
                         layout->instruction_size_bytes);

    return TENSIL_ERROR_NONE;
}

tensil_error_t tensil_buffer_append_noop_instructions(
    struct tensil_instruction_buffer *buffer,
    const struct tensil_instruction_layout *layout, size_t count) {
    size_t size = count * layout->instruction_size_bytes;
    if (size > buffer->size - buffer->offset)
        return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_INSUFFICIENT_BUFFER,
                                   "Program is too big");

    memset(buffer->ptr + buffer->offset, 0, size);

    Xil_DCacheFlushRange((UINTPTR)buffer->ptr + buffer->offset, size);

    buffer->offset += size;

    return TENSIL_ERROR_NONE;
}

tensil_error_t
tensil_buffer_append_program(struct tensil_instruction_buffer *buffer,
                             const uint8_t *ptr, size_t size) {
    if (size > buffer->size - buffer->offset)
        return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_INSUFFICIENT_BUFFER,
                                   "Program is too big");

    memcpy(buffer->ptr + buffer->offset, ptr, size);

    Xil_DCacheFlushRange((UINTPTR)buffer->ptr + buffer->offset, size);

    buffer->offset += size;

    return TENSIL_ERROR_NONE;
}

#ifdef TENSIL_PLATFORM_ENABLE_FILE_SYSTEM

tensil_error_t
tensil_buffer_append_program_from_file(struct tensil_instruction_buffer *buffer,
                                       size_t size, const char *file_name) {
    FIL fil;
    FILINFO fno;
    FRESULT res;
    UINT bytes_read;

    memset(&fno, 0, sizeof(FILINFO));
    res = f_stat(file_name, &fno);
    if (res)
        return TENSIL_FS_ERROR(res);

    if (size && fno.fsize != size)
        return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_UNEXPECTED_PROGRAM_SIZE,
                                   "Unexpected program size in %s", file_name);

    if (fno.fsize > buffer->size - buffer->offset)
        return TENSIL_DRIVER_ERROR(TENSIL_ERROR_DRIVER_INSUFFICIENT_BUFFER,
                                   "Program is too big in %s", file_name);

    memset(&fil, 0, sizeof(FIL));
    res = f_open(&fil, file_name, FA_READ);
    if (res)
        return TENSIL_FS_ERROR(res);

    res = f_read(&fil, (void *)(buffer->ptr + buffer->offset), fno.fsize,
                 &bytes_read);

    f_close(&fil);

    if (res)
        return TENSIL_FS_ERROR(res);

    Xil_DCacheFlushRange((UINTPTR)buffer->ptr + buffer->offset, fno.fsize);

    buffer->offset += fno.fsize;

    return TENSIL_ERROR_NONE;
}

#endif

tensil_error_t
tensil_buffer_pad_to_alignment(struct tensil_instruction_buffer *buffer,
                               const struct tensil_instruction_layout *layout,
                               int alignment_bytes) {
    while (buffer->offset & (alignment_bytes - 1)) {
        tensil_error_t error = tensil_buffer_append_instruction(
            buffer, layout, TENSIL_OPCODE_NOOP, 0, 0, 0, 0);

        if (error)
            return error;
    }
    return TENSIL_ERROR_NONE;
}

void tensil_buffer_reset(struct tensil_instruction_buffer *buffer) {
    buffer->offset = 0;
}
