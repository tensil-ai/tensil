# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

import numpy as np


def axi_data_type(axi_data_width):
    if axi_data_width == 8:
        return np.uint8
    elif axi_data_width == 16:
        return np.uint16
    elif axi_data_width == 32:
        return np.uint32
    elif axi_data_width == 64:
        return np.uint64
    elif axi_data_width == 128:
        return np.float128
    else:
        # TODO add numpy data type for 256, 512, 1024 bit uints
        raise Exception("axi data width {}-bit is not supported".format(axi_data_width))
