# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

from collections import namedtuple
from enum import Enum
import numpy as np


Params = namedtuple(
    "Params",
    [
        "width",
        "binary_point",
        "size_bytes",
    ],
)


class DataType(Enum):
    INVALID = Params(0, 0, 0)
    FP16BP8 = Params(16, 8, 2)


def data_type_size_bytes(data_type):
    """Returns the number of bytes the data type occupiers in host memory."""
    if data_type is DataType.FP16BP8:
        return 4
    else:
        raise Exception("data type {} not recognized".format(data_type))


def one(data_type):
    return data_type_numpy(data_type)(1) << data_type.value.binary_point


def data_type_numpy(data_type):
    """
    Returns the appropriate numpy dtype to represent the data type in host
    memory.
    """
    size_bytes = data_type.value.size_bytes
    if size_bytes == 1:
        return np.uint8
    elif size_bytes == 2:
        return np.uint16
    elif size_bytes == 4:
        return np.uint32
    elif size_bytes == 8:
        return np.uint64
    else:
        raise Exception(
            "data type {} of size {} is not supported".format(data_type, size_bytes)
        )
