# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

import pynq
import numpy as np
from tcu_pynq.data_type import data_type_numpy
from tcu_pynq.util import div_ceil
from tcu_pynq.config import Constant
from tcu_pynq.axi import axi_data_type


class Mem:
    """
    Mem provides a read and write interface to pynq contiguous memory.
    """

    def __init__(
        self,
        buf,
        data_type,
        debug=False,
    ):
        """
        Instantiates the contiguous array of memory that will be accessible to the fabric,
        configures the AXI memory port and computes offsets.

        Parameters
        ----------
        buf : PynqBuffer
            A buffer of contiguous memory allocated by Pynq
        data_type : DataType
            The data type of the scalars to be stored e.g. DataType.FP16BP8
        """
        self.buffer = buf
        self.data_type = data_type
        self.debug = debug
        self.data_type_numpy = data_type_numpy(self.data_type)
        self.data_type_numpy_size_bytes = self.data_type_numpy(0).nbytes
        self.mem = self.buffer.view(dtype=self.data_type_numpy)

    def write(self, offset, data):
        """data must be an np.array of type self.data_type_numpy"""
        if data.dtype != self.data_type_numpy:
            raise MemException(
                "data type must be {}, got {}".format(self.data_type_numpy, data.dtype)
            )
        data = data.reshape((-1,))
        self.mem[offset : offset + len(data)] = data
        if self.debug:
            print("wrote addr={} size={}".format(offset, len(data)))

    def read(self, offset, size):
        """returns an np.array of type self.data_type_numpy"""
        data = np.array(self.mem[offset : offset + size]).copy()
        if self.debug:
            print("read addr={} size={}".format(offset, size))
        return data

    def compare(self, offset, data):
        """returns an np.array of type self.data_type_numpy"""
        if data.dtype != self.data_type_numpy:
            raise MemException(
                "data type must be {}, got {}".format(self.data_type_numpy, data.dtype)
            )
        data = data.reshape((-1,))
        return np.array_equal(self.mem[offset : offset + len(data)], data)

    def write_bytes(self, offset_bytes, data):
        if offset_bytes % self.data_type_numpy_size_bytes != 0:
            raise MemException(
                "offset {} is not aligned with Mem's data type {} of size {} bytes".format(
                    offset_bytes,
                    self.data_type,
                    self.data_type_numpy_size_bytes,
                )
            )
        offset = offset_bytes // self.data_type_numpy_size_bytes
        data = np.frombuffer(data, dtype=self.data_type_numpy)
        self.write(offset, data)

    def read_bytes(self, offset_bytes, size_bytes):
        if offset_bytes % self.data_type_numpy_size_bytes != 0:
            raise MemException(
                "offset_bytes {} is not aligned with Mem's data type {} of size {} bytes".format(
                    offset_bytes,
                    self.data_type,
                    self.data_type_numpy_size_bytes,
                )
            )
        offset = offset_bytes // self.data_type_numpy_size_bytes
        if size_bytes % self.data_type_numpy_size_bytes != 0:
            raise MemException(
                "size_bytes {} is not aligned with Mem's data type {} of size {} bytes".format(
                    size_bytes,
                    self.data_type,
                    self.data_type_numpy_size_bytes,
                )
            )
        size = size_bytes // self.data_type_numpy_size_bytes
        data = self.read(offset, size)
        return data.tobytes()


class MemException(Exception):
    pass
