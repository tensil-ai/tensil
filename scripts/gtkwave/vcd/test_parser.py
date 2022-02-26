import unittest

from vcd.parser import *


class TestHeaderParser(unittest.TestCase):
    def test_next_statement(self):
        expected = [
            Statement(kind='date', data=['2021-02-12T20:50+0000']),
            Statement(kind='version', data=['0.2']),
            Statement(kind='comment', data=[]),
            Statement(kind='timescale', data=['1ns']),
            Statement(kind='scope', data=['module', 'Foo']),
            Statement(kind='var', data=['wire', '32', '%w', 'bar']),
            Statement(kind='upscope', data=[]),
            Statement(kind='enddefinitions', data=[]),
            Statement(kind='dumpvars', data=['b00000000000000000000000000000000', '%w']),
        ]
        with open('test_parser.vcd', 'r') as f:
            parser = HeaderParser(f)
            i = 0
            for stmt in parser:
                self.assertEqual(stmt, expected[i])
                i += 1


class TestValueChangeParser(unittest.TestCase):
    def test_next_line(self):
        expected = [
            Timestep(time=0),
            ValueChange(id='%w', value='b00000000000000000000000000000001'),
            Timestep(time=1),
            ValueChange(id='%w', value='b00000000000000000000000000000010'),
        ]
        with open('test_parser.vcd', 'r') as f:
            l = f.readline()
            while l[0] != '#':
                l = f.readline()
            parser = ValueChangeParser(f, int(l[1:]))
            i = 0
            for line in parser:
                self.assertEqual(line, expected[i])
                i += 1


class TestParser(unittest.TestCase):
    def test_next_node(self):
        expected = [
            Statement(kind='date', data=['2021-02-12T20:50+0000']),
            Statement(kind='version', data=['0.2']),
            Statement(kind='comment', data=[]),
            Statement(kind='timescale', data=['1ns']),
            Statement(kind='scope', data=['module', 'Foo']),
            Statement(kind='var', data=['wire', '32', '%w', 'bar']),
            Statement(kind='upscope', data=[]),
            Statement(kind='enddefinitions', data=[]),
            Statement(kind='dumpvars', data=['b00000000000000000000000000000000', '%w']),
            Timestep(time=0),
            ValueChange(id='%w', value='b00000000000000000000000000000001'),
            Timestep(time=1),
            ValueChange(id='%w', value='b00000000000000000000000000000010'),
        ]
        with open('test_parser.vcd', 'r') as f:
            parser = Parser(f)
            i = 0
            for node in parser:
                self.assertEqual(node, expected[i])
                i += 1


if __name__ == '__main__':
    unittest.main()