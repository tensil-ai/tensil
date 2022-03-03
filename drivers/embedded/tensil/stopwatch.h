/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#include "error.h"
#include "platform.h"

#if defined(TENSIL_PLATFORM_ENABLE_XTIME)
#include "xtime_l.h"
#elif defined(TENSIL_PLATFORM_STOPWATCH_TIMER_DEVICE_ID)
#include "xtmrctr.h"
#endif

struct stopwatch {
#if defined(TENSIL_PLATFORM_ENABLE_XTIME)
    XTime start;
    XTime end;
#elif defined(TENSIL_PLATFORM_STOPWATCH_TIMER_DEVICE_ID)
    XTmrCtr timer_counter;
    uint64_t stop_count;
#endif
};

error_t stopwatch_start(struct stopwatch *stopwatch);

void stopwatch_stop(struct stopwatch *stopwatch);

float stopwatch_elapsed_us(const struct stopwatch *stopwatch);

float stopwatch_elapsed_seconds(const struct stopwatch *stopwatch);
