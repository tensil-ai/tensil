# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

import unittest
from tcu_pynq.model import *
from tcu_pynq.architecture import Architecture
from tcu_pynq.data_type import DataType


class ModelFromJSONTest(unittest.TestCase):
    def testResNet20v2_CIFAR(self):
        blob = """{"name":"resnet20v2_cifar","prog":{"file_name":"resnet20v2_cifar.tprog"},"consts":[{"file_name":"resnet20v2_cifar.tdata","base":0,"size":71609}],"inputs":[{"name":"x","base":0,"size":1024}],"outputs":[{"name":"Identity","base":32,"size":2}],"arch":{"data_type":"FP16BP8","array_size":8,"dram0_depth":1048576,"dram1_depth":1048576,"local_depth":8192,"accumulator_depth":2048,"simd_registers_depth":1,"stride0_depth":8,"stride1_depth":8}}"""
        model = Model(
            name="resnet20v2_cifar",
            prog=Program(
                file_name="resnet20v2_cifar.tprog",
            ),
            consts=[
                Consts(
                    file_name="resnet20v2_cifar.tdata",
                    base=0,
                    size=71609,
                )
            ],
            inputs=[
                Vars(
                    name="x",
                    base=0,
                    size=1024,
                ),
            ],
            outputs=[
                Vars(
                    name="Identity",
                    base=32,
                    size=2,
                ),
            ],
            arch=Architecture(
                data_type=DataType.FP16BP8,
                array_size=8,
                dram0_depth=1048576,
                dram1_depth=1048576,
                local_depth=8192,
                accumulator_depth=2048,
                simd_registers_depth=1,
                stride0_depth=8,
                stride1_depth=8,
            ),
        )
        self.assertEqual(model_from_json(blob), model)
