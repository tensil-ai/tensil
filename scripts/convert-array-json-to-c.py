#!/usr/bin/env python3

import sys


def convert(c):
    if c == '[': return '{'
    if c == ']': return '}'
    return c


if __name__ == '__main__':
    if len(sys.argv) > 1 and (sys.argv[1] == '-h' or sys.argv[1] == '--help'):
        print('Usage of convert-array-json-to-c:')
        print('\tReads from stdin and replaces [ and ] with { and } respectively.')
        print('\tPrints result to stdout.')
        print('')
        print('Example usage:')
        print('\tcat data.json | python3 convert-array-json-to-c.py > data.c')
    json_array_s = sys.stdin.read()
    c_array_s = ''.join(map(convert, json_array_s))

    print(c_array_s, file=sys.stdout, flush=True)
