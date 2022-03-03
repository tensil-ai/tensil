# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

import numpy as np
from tcu_pynq.stream import DoubleBufferedAdapter
import unittest


class MockSendChannel:
    def __init__(self):
        self.max_buffer_size = 8
        self.dtype = np.uint8
        self.transfer_requested = False
        self.buffer = None
        self.written = np.array([], dtype=self.dtype)

    def make_buffer(self, size):
        return np.zeros(size, dtype=self.dtype)

    def transfer(self, buffer):
        if self.transfer_requested:
            raise Exception("transfer already in progress")
        self.buffer = buffer
        self.transfer_requested = True

    def wait(self):
        if self.transfer_requested:
            self.written = np.concatenate((self.written, self.buffer))
            self.transfer_requested = False

    def copy(self, src, src_offset, dst, dst_offset, length):
        dst[dst_offset : dst_offset + length] = src[src_offset : src_offset + length]

    def free(self, buffer):
        pass


class MockRecvChannel:
    def __init__(self):
        self.max_buffer_size = 8
        self.dtype = np.uint8
        self.transfer_requested = False
        self.buffer = None
        self.read = np.array([], dtype=self.dtype)
        self.index = 0

    def make_buffer(self, size):
        return np.zeros(size, dtype=self.dtype)

    def transfer(self, buffer):
        if self.transfer_requested:
            raise Exception("transfer already in progress")
        self.buffer = buffer
        self.transfer_requested = True

    def wait(self):
        if self.transfer_requested:
            self.buffer[:] = self.read[self.index : self.index + len(self.buffer)]
            self.index += len(self.buffer)
            self.transfer_requested = False

    def copy(self, src, src_offset, dst, dst_offset, length):
        dst[dst_offset : dst_offset + length] = src[src_offset : src_offset + length]

    def free(self, buffer):
        pass


class DoubleBufferedAdapterWriteTest(unittest.TestCase):
    def setUp(self):
        self.channel = MockSendChannel()
        self.adapter = DoubleBufferedAdapter(self.channel)

    def test_small_write(self):
        data = np.arange(self.channel.max_buffer_size, dtype=self.channel.dtype)
        self.adapter.write(data)
        np.testing.assert_array_equal(self.channel.written, data)

    def test_remainder_write(self):
        data = np.arange(self.channel.max_buffer_size + 1, dtype=self.channel.dtype)
        self.adapter.write(data)
        np.testing.assert_array_equal(self.channel.written, data)

    def test_large_write(self):
        data = np.arange(self.channel.max_buffer_size * 100, dtype=self.channel.dtype)
        self.adapter.write(data)
        np.testing.assert_array_equal(self.channel.written, data)


class DoubleBufferedAdapterReadTest(unittest.TestCase):
    def setUp(self):
        self.channel = MockRecvChannel()
        self.adapter = DoubleBufferedAdapter(self.channel)

    def test_small_read(self):
        size = self.channel.max_buffer_size
        self.channel.read = np.arange(size, dtype=self.channel.dtype)
        data = np.zeros(size, dtype=self.channel.dtype)
        self.adapter.read(data)
        np.testing.assert_array_equal(self.channel.read, data)

    def test_remainder_read(self):
        size = self.channel.max_buffer_size + 1
        self.channel.read = np.arange(size, dtype=self.channel.dtype)
        data = np.zeros(size, dtype=self.channel.dtype)
        self.adapter.read(data)
        np.testing.assert_array_equal(self.channel.read, data)

    def test_large_read(self):
        size = self.channel.max_buffer_size * 100
        self.channel.read = np.arange(size, dtype=self.channel.dtype)
        data = np.zeros(size, dtype=self.channel.dtype)
        self.adapter.read(data)
        np.testing.assert_array_equal(self.channel.read, data)
