# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

import unittest
import numpy as np
from tcu_pynq.allocator import *


class AllocatorTest(unittest.TestCase):
    def setUp(self):
        self.buffer = np.zeros((1 << 8,), dtype=np.uint64)
        self.allocator = Allocator(self.buffer, debug=True)

    def test_llocate(self):
        slices = [self.allocator.allocate(1) for i in range(len(self.buffer))]

    def test_free(self):
        slices = [self.allocator.allocate(1) for i in range(len(self.buffer))]
        [self.allocator.free(s) for s in slices]

    def test_random(self):
        slices = list()
        for i in range(100):
            if np.random.randint(2) == 0:
                # allocate
                # get random bit of available memory to ensure allocator should succeed
                if len(self.allocator.available) != 0:
                    _, length = self.allocator.available[
                        np.random.randint(len(self.allocator.available))
                    ]
                    slices.append(
                        self.allocator.allocate(np.random.randint(1, length + 1))
                    )
            else:
                # free
                if len(slices) != 0:
                    idx = np.random.randint(len(slices))
                    self.allocator.free(slices[idx])
                    del slices[idx]

    def test_set_data(self):
        a = self.allocator.allocate(1)
        b = self.allocator.allocate(1)
        a.buffer[0] = 1
        b.buffer[0] = 2
        self.assertEqual(a.buffer[0], 1)
        self.assertEqual(b.buffer[0], 2)

    def test_allocate_aligned(self):
        self.allocator._physical_address = 8
        s = self.allocator.allocate_aligned(1 << 5, 34)
        self.assertEqual(self.allocator.physical_address(s.address), 32)
