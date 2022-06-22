/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include <stdbool.h>

void console_set_cursor_position(int x, int y);

void console_clear_screen();

void console_set_foreground_color(int r, int g, int b);

void console_reset_foreground_color();

void console_set_background_color(int r, int g, int b);

void console_reset_background_color();

bool console_get_cursor_position(int *x, int *y);