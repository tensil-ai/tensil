import unittest

from vcd.emitter import *


class TestEmitter(unittest.TestCase):
    def test_emit(self):
        nodes = [
            Statement('date', ['2021-02-12T20:50+0000']),
            Statement('version', ['0.2']),
            Statement('comment', []),
            Statement('timescale', ['1ns']),
            Statement('var', ['wire', '1', 'a', 'foo']),
            Statement('scope', ['module', 'bar']),
            Statement('var', ['wire', '8', 'b', 'baz']),
            Statement('upscope', []),
            Statement('enddefinitions', []),
            Statement('dumpvars', ['0a', 'b00000000 b']),
            Timestep(0),
            ValueChange('a', '1'),
            Timestep(1),
            ValueChange('a', '0'),
            ValueChange('b', 'b10111011')
        ]
        expected = '''$date 2021-02-12T20:50+0000 $end
$version 0.2 $end
$comment  $end
$timescale 1ns $end
$var wire 1 a foo $end
$scope module bar $end
$var wire 8 b baz $end
$upscope  $end
$enddefinitions  $end
$dumpvars
0a
b00000000 b
$end
#0
1a
#1
0a
b10111011 b'''
        emitter = Emitter(iter(nodes))
        result = '\n'.join(emitter)
        self.assertEqual(result, expected)
        

if __name__ == '__main__':
    unittest.main()