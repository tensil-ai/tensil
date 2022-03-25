/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

#pragma once

#ifdef TENSIL_TARGET_ULTRA96_V2
#include "ff.h"
#define FATFS_UINT UINT
#endif

#ifdef TENSIL_TARGET_PYNQ_Z1
// #include "ff.h"
// #define FATFS_UINT UINT
#endif
