# SPDX-License-Identifier: Apache-2.0
# Copyright © 2019-2022 Tensil AI Company

import math
import numpy as np


def pad_to(array, length, pad=0):
    if len(array) >= length:
        return array
    return np.concatenate([array, np.zeros(length - len(array), dtype=array.dtype)])


def parent_dir(filename):
    return "/".join(filename.split("/")[:-1])


def reduce(seq, f, init):
    result = init
    for i in seq:
        result = f(i, result)
    return result


def div_ceil(num, den):
    return (num + den - 1) // den


def log2_ceil(x):
    i = 0
    while 2 ** i < x:
        i += 1
    return i


def round_size_bits(size):
    remainder = size % 8
    if remainder == 0:
        return size
    else:
        return size + 8 - remainder


def lcm(a, b):
    return abs(a * b) // math.gcd(a, b)


def to_fixed_point(x, width, bp):
    x = np.round(x * (1 << bp))
    c = 1 << width
    if x < 0:
        x = c + x
    return x


def from_fixed_point(x, width, bp):
    if x >= 1 << (width - 1):
        x = x - (1 << width)
    return x / (1 << bp)


def vector_from_fixed_point(width, binary_point):
    def inner(x):
        r = x.astype(np.int_)
        cond = x >= 1 << (width - 1)
        r[cond] -= 1 << width
        r = r / (1 << binary_point)
        return r

    return inner


def vector_to_fixed_point(width, binary_point):
    def inner(x):
        r = np.round(x * (1 << binary_point))
        r[r < 0] += 1 << width
        return r

    return inner
