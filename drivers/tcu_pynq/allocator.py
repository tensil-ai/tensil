# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

class Slice:
    def __init__(self, address, buf):
        self.address = address
        self.buffer = buf

    def __iter__(self):
        return self.buffer

    def __len__(self):
        return len(self.buffer)

    def __getitem__(self, indices):
        return self.buffer[indices]

    def __setitem__(self, indices, value):
        self.buffer[indices] = value

    def __repr__(self):
        return "Slice({}, {})".format(self.address, len(self.buffer))


class Allocator:
    def __init__(self, buf, debug=False):
        """
        Allocator manages the memory in an instance of PynqBuffer. We avoid
        using `pynq.allocate` because of a bug where it will not allocate any
        more memory after allocating one large block.

        Parameters
        -------------
        buf : PynqBuffer
            An instance of contiguous memory allocated by pynq
        """
        self.buffer = buf
        if len(self.buffer) == 0:
            raise Exception(
                "buffer must have non-zero length, got {}".format(self.buffer)
            )
        self.debug = debug
        self._physical_address = 0
        if hasattr(self.buffer, "physical_address"):
            self._physical_address = self.buffer.physical_address
        self.dtype_size_bytes = self.buffer[0].nbytes
        self.loc = dict()
        self.available = [(0, len(self.buffer))]

    def allocate(self, size):
        """
        Allocates a slice of memory and returns it.

        size : int
            The number of scalars to allocate
        """
        return self.allocate_aligned(1, size)

    def physical_address(self, address):
        return self._physical_address + address * self.dtype_size_bytes

    def allocate_aligned(self, block_size, size):
        """
        Allocates a slice of memory aligned to a block of physical addresses and returns it.

        size : int
            The number of scalars to allocate
        """
        # TODO use block bitness instead of block size since block will always be power of 2
        if self.debug:
            print(self.loc)
        if size <= 0:
            raise Exception("size must be non-negative int, got {}".format(size))
        succeeded = False
        for addr, length in self.available:
            skip_addresses = (
                block_size - (self.physical_address(addr) % block_size)
            ) // self.dtype_size_bytes
            if length >= size + skip_addresses:
                a = addr + skip_addresses
                self.loc[a] = Slice(a, self.buffer[a : a + size])
                succeeded = True
                break
        if not succeeded:
            raise Exception(
                "couldn't allocate slice of size {} given allocator state {}".format(
                    size, self.loc
                )
            )
        self.refresh_available(self.loc)
        if self.debug:
            print("allocated {}".format(self.loc[a]))
        return self.loc[a]

    def free(self, slc):
        if self.debug:
            print(self.loc)
            print("freeing {}".format(slc))
        del self.loc[slc.address]
        self.refresh_available(self.loc)

    def refresh_available(self, loc):
        used = [(addr, len(slc)) for addr, slc in loc.items()]
        used.sort(key=lambda p: p[0])
        available = list()
        addr = 0
        for next_addr, length in used:
            l = next_addr - addr
            if l > 0:
                available.append((addr, l))
            addr = next_addr + length
        l = len(self.buffer) - addr
        if l > 0:
            available.append((addr, l))
        self.available = available
