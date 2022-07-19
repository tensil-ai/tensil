# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

import numpy as np
from multiprocessing import Process
import pynq
from pynq.pl_server.xrt_device import _free_bo
import time

from tcu_pynq.util import (
    div_ceil,
    parent_dir,
    lcm,
    vector_to_fixed_point,
    vector_from_fixed_point,
)
from tcu_pynq.mem import Mem
from tcu_pynq.axi import axi_data_type
from tcu_pynq.data_type import data_type_numpy
from tcu_pynq.stream import Stream
from tcu_pynq.instruction import Layout
from tcu_pynq.instruction import DataMoveFlag
from tcu_pynq.config import Register, Constant
from tcu_pynq.allocator import Allocator
from tcu_pynq.model import model_from_json


class Driver:
    """
    TPUDriver provides methods for interacting with the TPU. Communication
    occurs via an AXI DMA core for instructions and 2 direct AXI connections
    for data.
    """

    def __init__(
        self,
        arch,
        axi_dma_instr,
        axi_dma_sample = None,
        dma_buffer_size=1048576,
        debug=False,
    ):
        """
        Sets up drivers for the AXI DMA core using the Xlnk memory mapper helper
        and then uses it to instantiate the DRAMs.

        Parameters
        ----------
        arch : Architecture
            An instance of Architecture containing architecture parameters
        axi_dma_instr : Instruction DMA from the overlay
            Instruction DMA
        axi_dma_sample : Sample DMA from the overlay
            Sample DMA
        debug : bool (optional)
            Enable debug messages
        """
        self.axi_dma_instr = axi_dma_instr
        self.arch = arch
        # TODO axi_dma_sample can have different width
        self.axi_data_width = int(self.axi_dma_instr.description['parameters']['C_M_AXI_MM2S_DATA_WIDTH'])
        self.axi_data_type = axi_data_type(self.axi_data_width)
        # TODO enable sample based on sample block size parameter in arch and set axi_dma_sample
        self.axi_dma_sample = axi_dma_sample
        self.dma_buffer_size = dma_buffer_size
        self.debug = debug
        self.model = None

        max_dma_buffer_size = max(
            dma.buffer_max_size if dma else 0
            for dma in [self.axi_dma_instr, self.axi_dma_sample]
        )
        if self.dma_buffer_size > max_dma_buffer_size:
            raise Exception(
                "DMA buffer size {} exceeded max value of {}".format(
                    self.dma_buffer_size, max_dma_buffer_size
                )
            )

        # setup instruction and status AXI DMA
        # self.instruction_buffer_width = lcm(self.instruction_width, self.axi_data_width)
        self.layout = Layout(self.arch)
        if self.debug:
            print(self.layout)

        # allocate buffer for DRAMs
        scalars_per_word = (
            self.axi_data_type(0).nbytes
            // data_type_numpy(self.arch.data_type)(0).nbytes
        )
        tcu_block_size = Constant.TCU_BLOCK_SIZE.value
        depth = (self.arch.dram0_depth + self.arch.dram1_depth) * self.arch.array_size
        extra = tcu_block_size // (self.axi_data_width // 8)
        buffer_size = depth // scalars_per_word + extra + 3 * self.dma_buffer_size
        if self.axi_dma_sample:
            buffer_size += 3 * self.dma_buffer_size
        if self.debug:
            print(
                "allocating buffer of dtype {} of size {}".format(
                    self.axi_data_type, buffer_size
                )
            )
        self.buffer = pynq.allocate((buffer_size,), dtype=self.axi_data_type)
        # allocate DRAMs within buffer
        self.allocator = Allocator(self.buffer, debug=self.debug)
        dram0_length = self.arch.dram0_depth * self.arch.array_size // scalars_per_word
        self.dram0_buffer = self.allocator.allocate_aligned(
            tcu_block_size, dram0_length
        ).buffer
        dram1_length = self.arch.dram1_depth * self.arch.array_size // scalars_per_word
        self.dram1_buffer = self.allocator.allocate_aligned(
            tcu_block_size, dram1_length
        ).buffer
        dram0_address_offset = div_ceil(
            self.dram0_buffer.physical_address, tcu_block_size
        )
        dram1_address_offset = div_ceil(
            self.dram1_buffer.physical_address, tcu_block_size
        )
        if self.debug:
            print(
                "DRAM0: length = {}, offset = {}".format(
                    dram0_length, hex(dram0_address_offset)
                )
            )
            print(
                "DRAM1: length = {}, offset = {}".format(
                    dram1_length, hex(dram1_address_offset)
                )
            )
        # instantiate DRAMs
        self.dram0 = Mem(
            self.dram0_buffer,
            self.arch.data_type,
            debug=self.debug,
        )
        self.dram1 = Mem(
            self.dram1_buffer,
            self.arch.data_type,
            debug=self.debug,
        )
        # instantiate streams
        self.instruction_stream = Stream(
            self.axi_dma_instr,
            self.dma_buffer_size,
            self.axi_data_width,
            self.allocator,
        )
        if self.axi_dma_sample:
            self.sample_data_type = np.uint64
            self.sample_stream = Stream(
                self.axi_dma_sample,
                self.dma_buffer_size,
                self.axi_data_width,
                self.allocator,
            )

        # set address offsets
        self.configure(
            (Register.DRAM0_ADDRESS_OFFSET, dram0_address_offset),
            (Register.DRAM1_ADDRESS_OFFSET, dram1_address_offset),
            (Register.TIMEOUT, 100),
        )

    def __del__(self):
        self.close()

    def close(self):
        if hasattr(self, "buffer"):
            _free_bo(
                self.buffer.device,
                self.buffer.bo,
                self.buffer.virtual_address,
                self.buffer.nbytes,
            )
            self.buffer.freebuffer()

    def write_instructions(self, instructions):
        """instructions should be a sequence of ints"""
        prog = bytes()
        for i in instructions:
            prog = prog + self.layout.to_bytes(i)
        self.instruction_stream.write(prog, align=self.layout.instruction_size_bytes)

    def configure(self, *pairs):
        program = [
            self.layout.configure(register.value, value) for register, value in pairs
        ] + self.prepare_flush_probe()
        self.write_instructions(program)
        self.wait_for_flush()

    def run_load_consts(self, offset, size):
        program = [
            self.layout.data_move(
                DataMoveFlag.dram1_to_memory,
                offset,
                offset,
                size - 1)
        ] + self.prepare_flush_probe()
        self.write_instructions(program)
        self.wait_for_flush()

    def load_model(self, model_filename):
        self.model_filename = model_filename
        with open(self.model_filename, "r") as f:
            self.model = model_from_json(f.read())
        # check that model arch matches driver arch
        if not (self.model.arch == self.arch):
            raise Exception(
                "model requires architecture {} but current architecture is {}".format(
                    self.model.arch, self.arch
                )
            )
        # load consts and program
        d = parent_dir(self.model_filename) + "/"
        for const in self.model.consts:
            with open(d + const.file_name, "rb") as f:
                self.dram1.write_bytes(
                    const.base
                    * self.arch.array_size
                    * self.dram1.data_type_numpy_size_bytes,
                    f.read(),
                )
            if self.model.load_consts_to_local:
                self.run_load_consts(const.base, const.size)
        with open(d + self.model.prog.file_name, "rb") as f:
            self.program = f.read()

    def scalar_address(self, vec_address):
        return vec_address * self.arch.array_size

    def prepare_flush_probe(self): 
        # initialize flush probe
        self.probe_source_address = self.arch.dram0_depth - 1
        self.probe_target_address = self.arch.dram0_depth - 2
        self.local_address = self.arch.local_depth - 1
        self.probe_source = np.full(
            self.arch.array_size,
            np.iinfo(data_type_numpy(self.arch.data_type)).max,
            dtype=data_type_numpy(self.arch.data_type))
        self.probe_target = np.full(
            self.arch.array_size,
            0,
            dtype=data_type_numpy(self.arch.data_type))
        
        # write flush probe
        self.dram0.write(
            self.scalar_address(self.probe_source_address),
            self.probe_source)
        self.dram0.write(
            self.scalar_address(self.probe_target_address),
            self.probe_target)

        # flush probe instructions
        return [
            self.layout.data_move(
                DataMoveFlag.dram0_to_memory,
                self.local_address,
                self.probe_source_address,
                0),
            self.layout.data_move(
                DataMoveFlag.memory_to_dram0,
                self.local_address,
                self.probe_target_address,
                0)
        ]

    def wait_for_flush(self):
        while not self.dram0.compare(
            self.scalar_address(self.probe_target_address),
            self.probe_source):
            pass

    def run(self, inputs):
        """
        Runs the model and returns outputs as a dict.

        inputs must be a dictionary containing a key for every input
        specified in the tmodel file. Each value in the dict must be
        a numpy array
        """
        import time

        start = time.time()
        prev = start

        def timestamp(event):
            nonlocal prev
            now = time.time()
            if self.debug:
                print("{}\t{:.3}s\t{:.3}s".format(event, now - prev, now - start))
            prev = now

        if self.model is None:
            raise Exception("model not loaded: please run driver.load_model first")

        # load inputs
        for inp in self.model.inputs:
            data = self.to_fixed(inputs[inp.name]).astype(
                data_type_numpy(self.arch.data_type)
            )
            self.dram0.write(self.scalar_address(inp.base), data)
        timestamp("wrote inputs")

        # append flush probe instructions
        prog = self.program
        for i in self.prepare_flush_probe():
            prog = prog + self.layout.to_bytes(i)

        # write program
        self.instruction_stream.write(
            prog, align=self.layout.instruction_size_bytes
        )

        self.wait_for_flush()
        timestamp("wrote program")
        
        # return outputs
        outputs = dict()
        for out in self.model.outputs:
            data = self.from_fixed(
                self.dram0.read(self.scalar_address(out.base), self.scalar_address(out.size))
            )
            if out.name in outputs:
                outputs[out.name] = np.concatenate([outputs[out.name], data])
            else:
                outputs[out.name] = data
        timestamp("read outputs")
        return outputs

    def to_fixed(self, arr):
        return vector_to_fixed_point(
            self.arch.data_type.value.width, self.arch.data_type.value.binary_point
        )(arr)

    def from_fixed(self, arr):
        return vector_from_fixed_point(
            self.arch.data_type.value.width, self.arch.data_type.value.binary_point
        )(arr)
