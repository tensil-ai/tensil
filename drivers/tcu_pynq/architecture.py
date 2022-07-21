# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

from collections import namedtuple
from tcu_pynq.data_type import DataType

Architecture = namedtuple(
    "Architecture",
    [
        "data_type",  # DataType
        "array_size",  # int
        "dram0_depth",  # int
        "dram1_depth",  # int
        "local_depth",  # int
        "accumulator_depth",  # int
        "simd_registers_depth",  # int
        "stride0_depth",  # int
        "stride1_depth",  # int
        "number_of_threads", # int
        "thread_queue_depth", # int
    ],
)

pynqz1 = Architecture(
    data_type=DataType.FP16BP8,
    array_size=8,
    dram0_depth=1048576,
    dram1_depth=1048576,
    local_depth=8192,
    accumulator_depth=2048,
    simd_registers_depth=1,
    stride0_depth=8,
    stride1_depth=8,
    number_of_threads=1,
    thread_queue_depth=8,
)


ultra96 = Architecture(
    data_type=DataType.FP16BP8,
    array_size=16,
    dram0_depth=2097152,
    dram1_depth=2097152,
    local_depth=20480,
    accumulator_depth=4096,
    simd_registers_depth=1,
    stride0_depth=8,
    stride1_depth=8,
    number_of_threads=1,
    thread_queue_depth=8,
)

zcu104 = Architecture(
    data_type=DataType.FP16BP8,
    array_size=32,
    dram0_depth=2097152,
    dram1_depth=2097152,
    local_depth=16384,
    accumulator_depth=4096,
    simd_registers_depth=1,
    stride0_depth=8,
    stride1_depth=8,
    number_of_threads=1,
    thread_queue_depth=8,
)

zcu104_uram = Architecture(
    data_type=DataType.FP16BP8,
    array_size=32,
    dram0_depth=2097152,
    dram1_depth=2097152,
    local_depth=49152,
    accumulator_depth=20480,
    simd_registers_depth=1,
    stride0_depth=8,
    stride1_depth=8,
    number_of_threads=1,
    thread_queue_depth=8,
)
