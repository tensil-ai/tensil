# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

from enum import Enum


class Constant(Enum):
    TCU_BLOCK_SIZE = 0x1 << 16
    AFI_RDCHAN_CTRL_OFFSET = 0x00
    AFI_WRCHAN_CTRL_OFFSET = 0x14


class Register(Enum):
    DRAM0_ADDRESS_OFFSET = 0x00
    DRAM0_CACHE_BEHAVIOUR = 0x01
    DRAM1_ADDRESS_OFFSET = 0x04
    DRAM1_CACHE_BEHAVIOUR = 0x05
    TIMEOUT = 0x08
    TRACEPOINT = 0x09
    PROGRAM_COUNTER = 0x0A
    SAMPLE_INTERVAL = 0x0B
