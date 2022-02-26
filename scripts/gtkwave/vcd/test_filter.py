import unittest

from vcd.parser import Parser
from vcd.filter import *


class TestSignalFilter(unittest.TestCase):
    def test_filter(self):
        signals = [
            'foo',
            'bar.baz',
            'bar.io.foo',
        ]
        expected = [
            Statement(kind='date', data=['2021-02-12T20:50+0000']),
            Statement(kind='version', data=['0.2']),
            Statement(kind='comment', data=[]),
            Statement(kind='timescale', data=['1ns']),
            Statement(kind='var', data=['wire', '1', 'a', 'foo']),
            Statement(kind='scope', data=['module', 'bar']),
            Statement(kind='var', data=['wire', '32', '%w', 'baz']),
            Statement(kind='scope', data=['module', 'io']),
            Statement(kind='var', data=['wire', '1', 'b', 'foo']),
            Statement(kind='upscope', data=[]),
            Statement(kind='upscope', data=[]),
            Statement(kind='enddefinitions', data=[]),
            Statement(kind='dumpvars', data=['0a', 'b00000000000000000000000000000000 %w', '0b']),
            Timestep(time=0),
            ValueChange(id='a', value='1'),
            ValueChange(id='%w', value='b00000000000000000000000000000001'),
            Timestep(time=1),
            ValueChange(id='b', value='1'),
            ValueChange(id='%w', value='b00000000000000000000000000000010'),
        ]
        with open('test_filter.vcd', 'r') as f:
            parser = Parser(f)
            filter_ = SignalFilter(parser, signals)
            i = 0
            for n in filter_:
                self.assertEqual(n, expected[i])
                i += 1


if __name__ == '__main__':
    unittest.main()