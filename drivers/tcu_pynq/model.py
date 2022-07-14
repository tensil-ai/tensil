# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

from collections import namedtuple
import json
from tcu_pynq.architecture import Architecture
from tcu_pynq.data_type import DataType

Model = namedtuple(
    "Model",
    [
        "name",  # str
        "prog",  # Program
        "consts",  # List[Consts]
        "inputs",  # List[Vars]
        "outputs",  # List[Vars]
        "arch",  # Architecture
        "load_consts_to_local", # bool
    ],
)

Program = namedtuple(
    "Program",
    [
        "file_name",  # str
        "size",  # int
    ],
)

Consts = namedtuple(
    "Consts",
    [
        "file_name",  # str
        "base",  # int
        "size",  # int
    ],
)

Vars = namedtuple(
    "Vars",
    [
        "name",  # str
        "base",  # int
        "size",  # int
    ],
)


def model_from_json(s):
    d = json.loads(s)
    d["prog"] = Program(**d["prog"])
    d["consts"] = [Consts(**consts) for consts in d["consts"]]
    d["arch"]["data_type"] = DataType[d["arch"]["data_type"]]
    d["arch"] = Architecture(**d["arch"])
    d["inputs"] = [Vars(**inp) for inp in d["inputs"]]
    d["outputs"] = [Vars(**out) for out in d["outputs"]]
    return Model(**d)
