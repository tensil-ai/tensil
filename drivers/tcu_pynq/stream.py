# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

import math
import numpy as np
import pynq
from tcu_pynq.axi import axi_data_type
from tcu_pynq.data_type import data_type_numpy
from tcu_pynq.util import lcm, div_ceil, reduce


class Channel:
    """
    Channel encapsulates the functionality of a DMA channel. Its interface
    consists of four methods and one property:

    make_buffer : (size) => (buffer)
        Makes a buffer of a given size
    transfer : (buffer) => ()
        Initiates the transfer
    wait : () => ()
        Waits for the transfer to complete
    copy : (buffer, offset, buffer, offset, length) => ()
        Copies from left buffer to right buffer
    free : (buffer) => ()
        Frees the buffers resources
    max_buffer_size : int
        The maximum allowable size for a buffer
    allocator : Allocator
        An instance of Allocator
    """

    def __init__(self, dma_channel, axi_data_width, max_buffer_size, allocator):
        self.dma_channel = dma_channel
        self.axi_data_width = axi_data_width
        self.max_buffer_size = max_buffer_size
        self.allocator = allocator

    def make_buffer(self, size):
        return self.allocator.allocate(size)

    def transfer(self, buf):
        return self.dma_channel.transfer(buf.buffer)

    def wait(self):
        return self.dma_channel.wait()

    def copy(self, src, src_offset, dst, dst_offset, length):
        dst[dst_offset : dst_offset + length] = src[src_offset : src_offset + length]

    def free(self, buf):
        self.allocator.free(buf)


class DoubleBufferedAdapter:
    def __init__(self, channel):
        self.channel = channel
        self.make_buffer = self.channel.make_buffer
        self.transfer = self.channel.transfer
        self.wait = self.channel.wait
        self.copy = self.channel.copy
        self.free = self.channel.free
        self.axi_data_width = self.channel.axi_data_width
        self.max_buffer_size = self.channel.max_buffer_size        

        # state
        self.data = None  # data to read/write
        self.total_length = 0  # total number of transfers required
        self.buffer_size = 0 # size of each buffer in the pair
        self.remainder_buffer_size = 0 # size of first remainder buffer if total_length % buffer_size == 0
        self.buffer = list()  # buffers to read and write
        self.current = 2  # index of current buffer
        self.alternate = 0  # index of alternate buffer
        self.transferred = 0  # number of words transferred
        self.index = 0  # index to copy from/to
        self.first = True  # first iteration of loop

    def read(self, data):
        """Reads into buffer data"""
        self._loop(data, False, 1)

    def write(self, data, align=1):
        """Writes from buffer data"""
        self._loop(data, True, align)

    def _init_buffers(self, total_length, align):
        self.total_length = total_length

        align_with_axi = align // math.gcd(align, self.axi_data_width // 8)
        self.buffer_size = self.max_buffer_size - (self.max_buffer_size % align_with_axi)

        self.remainder_buffer_size = self.total_length % self.buffer_size
        self.buffer = [
            self.make_buffer(self.buffer_size),
            self.make_buffer(self.buffer_size),
            self.make_buffer(self.remainder_buffer_size),
        ]

    def _del_buffers(self):
        for buf in self.buffer:
            self.free(buf)
        self.buffer = list()

    def _loop(self, data, write, align):
        """
        write : bool
            False = read, True = write
        """
        self.data = data
        self._init_buffers(len(self.data), align)
        self.current = 2 if self.remainder_buffer_size > 0 else 0
        self.next = 1
        self.prev = -1
        self.transferred = 0
        self.index = 0

        if write:
            self.copy(
                self.data,
                self.index,
                self.buffer[self.current],
                0,
                len(self.buffer[self.current]),
            )
            self.index += len(self.buffer[self.current])

        while self.transferred < self.total_length:
            self.transfer(self.buffer[self.current])
            self.transferred += len(self.buffer[self.current])
            if write:
                # only necessary if there is another transferred needed
                if self.index + len(self.buffer[self.next]) <= self.total_length:
                    self.copy(
                        self.data,
                        self.index,
                        self.buffer[self.next],
                        0,
                        len(self.buffer[self.next]),
                    )
                    self.index += len(self.buffer[self.next])
            else:                
                # only necessary if we've already done a transfer
                if self.prev >= 0:
                    self.copy(
                        self.buffer[self.prev],
                        0,
                        self.data,
                        self.index,
                        len(self.buffer[self.prev]),
                    )
                    self.index += len(self.buffer[self.prev])
            self.prev = self.current
            self.current = self.next
            self.next = (self.next + 1) % 2
            self.wait()
        if not write:
            self.copy(
                self.buffer[self.prev],
                0,
                self.data,
                self.index,
                len(self.buffer[self.prev]),
            )
        self._del_buffers()


class Stream:
    """
    Stream provides a read and write interface to AXI DMA transfers
    """

    def __init__(self, dma, max_buffer_size, axi_data_width, allocator):
        """
        Parameters
        --------------
        dma : DMA
            An instance of a pynq DMA
        max_buffer_size : int
            The maximum allowable size for a buffer
        axi_data_width : int
            Width in bits of the AXI data bus
        allocator : Allocator
            An instance of Allocator
        """
        self.dma = dma
        self.max_buffer_size = max_buffer_size
        self.axi_data_width = axi_data_width
        if self.axi_data_width % 8 != 0:
            raise Exception(
                "axi_data_width {} is not a multiple of 8 bits".format(
                    self.axi_data_width
                )
            )
        self.allocator = allocator
        self.axi_data_type = axi_data_type(axi_data_width)
        self.send_channel = Channel(
            self.dma.sendchannel,
            self.axi_data_width,
            self.max_buffer_size,
            self.allocator,
        )
        self.send_adapter = DoubleBufferedAdapter(self.send_channel)
        self.receive_channel = Channel(
            self.dma.recvchannel,
            self.axi_data_width,
            self.max_buffer_size,
            self.allocator,
        )
        self.receive_adapter = DoubleBufferedAdapter(self.receive_channel)

    def read(self, size):
        transfer_size = div_ceil(size, self.axi_data_width // 8)
        result = np.zeros(transfer_size, dtype=self.axi_data_type)
        self.receive_adapter.read(result)
        return result.view(np.uint8)[:size].tobytes()

    def write(self, data, align=1):
        """
        Writes bytes to the stream. Null bytes will be written such that the
        total number of bytes written to the stream is a multiple of align.

        Parameters
        ---------
        data : bytes
            an array of bytes or byte buffer
        align : int (optional) >= 1
            word size in bytes to align the stream on
        """
        align_with_axi = lcm(align, self.axi_data_width // 8)
        remainder = align_with_axi - (len(data) % align_with_axi)
        if remainder != 0:
            data = data + bytes([0 for i in range(remainder)])
        write_data = np.frombuffer(data, dtype=np.uint8).view(self.axi_data_type)
        self.send_adapter.write(write_data, align)
