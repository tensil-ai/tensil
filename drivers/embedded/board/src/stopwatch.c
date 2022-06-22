/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#include "stopwatch.h"

#include <math.h>

tensil_error_t stopwatch_start(struct stopwatch *stopwatch) {
#if defined(STOPWATCH_XTIME)
    XTime_GetTime(&stopwatch->start);
#elif defined(STOPWATCH_TIMER_DEVICE_ID)
    stopwatch->stop_count = 0;

    int status = XTmrCtr_Initialize(&stopwatch->timer_counter,
                                    STOPWATCH_TIMER_DEVICE_ID);
    if (status != XST_SUCCESS)
        return TENSIL_XILINX_ERROR(status);

    XTmrCtr_Reset(&stopwatch->timer_counter, 0);
    XTmrCtr_Reset(&stopwatch->timer_counter, 1);

    XTmrCtr_SetOptions(&stopwatch->timer_counter, 0, XTC_CASCADE_MODE_OPTION);
    XTmrCtr_Start(&stopwatch->timer_counter, 0);
#endif
    return TENSIL_ERROR_NONE;
}

void stopwatch_stop(struct stopwatch *stopwatch) {
#if defined(STOPWATCH_XTIME)
    XTime_GetTime(&stopwatch->end);
#elif defined(STOPWATCH_TIMER_DEVICE_ID)
    XTmrCtr_Stop(&stopwatch->timer_counter, 0);

    uint32_t high_count = XTmrCtr_GetValue(&stopwatch->timer_counter, 1);
    uint32_t low_count = XTmrCtr_GetValue(&stopwatch->timer_counter, 0);

    stopwatch->stop_count = (((uint64_t)high_count) << 32) + low_count;
#endif
}

#define US_PER_SECOND 1000000.0

float stopwatch_elapsed_us(const struct stopwatch *stopwatch) {
#if defined(STOPWATCH_XTIME)
    return ((float)(stopwatch->end - stopwatch->start) /
            ((float)COUNTS_PER_SECOND / US_PER_SECOND));
#elif defined(STOPWATCH_TIMER_DEVICE_ID)
    return ((float)stopwatch->stop_count /
            ((float)stopwatch->timer_counter.Config.SysClockFreqHz /
             US_PER_SECOND));
#else
    return NAN;
#endif
}

float stopwatch_elapsed_seconds(const struct stopwatch *stopwatch) {
#if defined(STOPWATCH_XTIME)
    return ((float)(stopwatch->end - stopwatch->start) /
            (float)COUNTS_PER_SECOND);
#elif defined(STOPWATCH_TIMER_DEVICE_ID)
    return ((float)stopwatch->stop_count /
            (float)stopwatch->timer_counter.Config.SysClockFreqHz);
#else
    return NAN;
#endif
}