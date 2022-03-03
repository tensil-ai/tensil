# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

import numpy as np
import time
import unittest
from collections import namedtuple
from pynq import Overlay
from tcu_pynq.data_type import data_type_numpy, one
from tcu_pynq.instruction import DataMoveFlag
from tcu_pynq.driver import Driver
from tcu_pynq.architecture import tiny
from tcu_pynq.util import pad_to


TestCase = namedtuple("TestCase", ["input", "expected"])

home = "/home/xilinx/"
bitstream = home + "pynq-z1-tcu-tiny.bit"
xor = home + "xor.tmodel"
resnet = home + "resnet20v2_cifar.tmodel"
yolo = home + "yolov4_tiny_192.tmodel"


class DiagnosticArchTiny(unittest.TestCase):
    def setUp(self, overlay=None, driver=None):
        self.overlay = overlay
        self.driver = driver
        if self.driver is None:
            if self.overlay is None:
                self.overlay = Overlay(bitstream)
            # Note: Uncomment the following line if using ILA debugger.
            #       You'll need to refresh the device in the Vivado HW
            #       Manager before hitting Enter to continue.
            # input("Overlay downloaded. Continue? [Enter]")
            self.driver = Driver(
                tiny,
                self.overlay.axi_dma_0,
                # Note: Uncomment the following line to enable debug
                #       messages.
                # debug=True,
            )
        else:
            self.overlay = self.driver.overlay

    def test_local_memory(self):
        # write some stuff to dram0
        size = self.driver.arch.local_depth * self.driver.arch.array_size
        data = np.arange(size, dtype=data_type_numpy(self.driver.arch.data_type))
        self.driver.dram0.write(0, data)
        # move data on and off
        program = [
            self.driver.layout.data_move(
                DataMoveFlag.dram0_to_memory, 0, 0, self.driver.arch.local_depth - 1
            ),
            self.driver.layout.data_move(
                DataMoveFlag.memory_to_dram0,
                0,
                self.driver.arch.local_depth,
                self.driver.arch.local_depth - 1,
            ),
        ]
        self.driver.write_instructions(program)
        # read it from dram0
        result = self.driver.dram0.read(
            self.driver.arch.local_depth * self.driver.arch.array_size, size
        )
        np.testing.assert_array_equal(result, data)

    def test_accumulator_memory(self):
        size = self.driver.arch.accumulator_depth * self.driver.arch.array_size
        data = np.arange(size, dtype=data_type_numpy(self.driver.arch.data_type))
        self.driver.dram0.write(0, data)
        program = [
            self.driver.layout.data_move(
                DataMoveFlag.dram0_to_memory,
                0,
                0,
                self.driver.arch.accumulator_depth - 1,
            ),
            self.driver.layout.data_move(
                DataMoveFlag.memory_to_accumulator,
                0,
                0,
                self.driver.arch.accumulator_depth - 1,
            ),
            self.driver.layout.data_move(
                DataMoveFlag.accumulator_to_memory,
                self.driver.arch.accumulator_depth,
                0,
                self.driver.arch.accumulator_depth - 1,
            ),
            self.driver.layout.data_move(
                DataMoveFlag.memory_to_dram0,
                self.driver.arch.accumulator_depth,
                self.driver.arch.accumulator_depth,
                self.driver.arch.accumulator_depth - 1,
            ),
        ]
        self.driver.write_instructions(program)
        result = self.driver.dram0.read(size, size)
        np.testing.assert_array_equal(result, data)

    def test_matmul(self):
        size = self.driver.arch.array_size ** 2
        data = np.arange(size, dtype=data_type_numpy(self.driver.arch.data_type))
        weights = np.identity(
            self.driver.arch.array_size,
            dtype=data_type_numpy(self.driver.arch.data_type),
        ) * one(self.driver.arch.data_type)
        input_address = 0
        output_address = self.driver.arch.array_size
        weights_address = self.driver.arch.local_depth - 1 - self.driver.arch.array_size
        self.driver.dram1.write(0, weights)
        self.driver.dram0.write(0, data)
        program = [
            self.driver.layout.data_move(
                DataMoveFlag.dram1_to_memory,
                weights_address,
                0,
                self.driver.arch.array_size - 1,
            ),
            self.driver.layout.data_move(
                DataMoveFlag.dram0_to_memory,
                input_address,
                0,
                self.driver.arch.array_size - 1,
            ),
            self.driver.layout.load_weight(
                False, weights_address, self.driver.arch.array_size - 1
            ),
            self.driver.layout.load_weight(True, 0, 0),
            self.driver.layout.matmul(False, 0, 0, self.driver.arch.array_size - 1),
            self.driver.layout.data_move(
                DataMoveFlag.accumulator_to_memory,
                output_address,
                0,
                self.driver.arch.array_size - 1,
            ),
            self.driver.layout.data_move(
                DataMoveFlag.memory_to_dram0,
                output_address,
                output_address,
                self.driver.arch.array_size - 1,
            ),
        ]
        program += [self.driver.layout.no_op() for i in range(1000)]
        self.driver.write_instructions(program)
        result = self.driver.dram0.read(
            output_address * self.driver.arch.array_size, size
        )
        np.testing.assert_array_equal(result, data)

    def test_dram1(self):
        # write some stuff to dram0
        size = self.driver.arch.local_depth * self.driver.arch.array_size
        data = np.arange(size, dtype=data_type_numpy(self.driver.arch.data_type))
        self.driver.dram1.write(0, data)
        # move data on and off
        program = [
            self.driver.layout.data_move(
                DataMoveFlag.dram1_to_memory, 0, 0, self.driver.arch.local_depth - 1
            ),
            self.driver.layout.data_move(
                DataMoveFlag.memory_to_dram0,
                0,
                self.driver.arch.local_depth,
                self.driver.arch.local_depth - 1,
            ),
        ]
        self.driver.write_instructions(program)
        # read it from dram0
        result = self.driver.dram0.read(
            self.driver.arch.local_depth * self.driver.arch.array_size, size
        )
        np.testing.assert_array_equal(result, data)

    def test_xor(self):
        test_case = [
            TestCase(input=(0, 0), expected=(0,)),
            TestCase(input=(0, 1), expected=(1,)),
            TestCase(input=(1, 0), expected=(1,)),
            TestCase(input=(1, 1), expected=(0,)),
        ]
        self.driver.load_model(xor)
        for case in test_case:
            dtype = data_type_numpy(self.driver.arch.data_type)
            input_ = pad_to(
                np.array(case.input, dtype=dtype), self.driver.arch.array_size
            )
            output = self.driver.run({"x": input_})["Identity"]
            expected = pad_to(
                np.array(case.expected, dtype=dtype), self.driver.arch.array_size
            )
            np.testing.assert_allclose(expected, output, atol=1e-02)

    def tearDown(self):
        self.driver.close()
