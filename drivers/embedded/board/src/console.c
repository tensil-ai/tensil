/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#include "console.h"

#include <stdio.h>
#include <string.h>

#include "xil_printf.h"

void console_set_cursor_position(int x, int y) {
    printf("\033[%d;%dH", x, y);
    fflush(stdout);
}

void console_clear_screen() {
    printf("\033[2J");
    fflush(stdout);
}

void console_set_foreground_color(int r, int g, int b) {
    printf("\033[38;2;%d;%d;%dm", r, g, b);
    fflush(stdout);
}

void console_reset_foreground_color() {
    printf("\033[39m");
    fflush(stdout);
}

void console_set_background_color(int r, int g, int b) {
    printf("\033[48;2;%d;%d;%dm", r, g, b);
    fflush(stdout);
}

void console_reset_background_color() {
    printf("\033[49m");
    fflush(stdout);
}

#define ESC_BUFFER_SIZE 128

bool console_get_cursor_position(int *x, int *y) {
    printf("\033[6n");
    fflush(stdout);

    char b = inbyte();

    if (b == '\033') {
        char buff[ESC_BUFFER_SIZE];
        memset(buff, 0, ESC_BUFFER_SIZE);

        for (size_t i = 0; i < ESC_BUFFER_SIZE; i++) {
            buff[i] = b;

            if (b == 'R')
                break;

            b = inbyte();
        }

        sscanf(buff, "\033[%d;%dR", x, y);
        return true;
    }

    return false;
}