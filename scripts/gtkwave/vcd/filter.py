from collections import namedtuple

from vcd.parser import Statement, Timestep, ValueChange, parse_value_change


# TODO move these to the parser
VarStatement = namedtuple('VarStatement', ['type', 'width', 'symbol', 'name'])
ScopeStatement = namedtuple('ScopeStatement', ['type', 'name'])

vcd_keywords = ['timescale', 'version', 'comment', 'date', 'enddefinitions']


# TODO add a filter to delete empty modules


def node_to_var(node):
    if type(node) == VarStatement:
        return node
    elif type(node) != Statement:
        raise Exception("VarStatement must be a Statement")
    return VarStatement(*node.data)


def node_to_scope(node):
    if type(node) == ScopeStatement:
        return node
    if type(node) != Statement:
        raise Exception("ScopeStatement must be a Statement")
    return ScopeStatement(*node.data)


class SignalFilter:
    def __init__(self, parser, signals):
        '''signals should be a list of strings naming the
        signals to be kept'''
        self.parser = parser
        self.signals = signals
        self.scopes = list()
        self.symbols_to_keep = set()
        self.valid_values = '01xXzZ'
    
    def filter(self, node):
        if type(node) == Statement:
            if node.kind == 'var':
                var = node_to_var(node)
                name = '.'.join(self.scopes + [var.name])
                if name in self.signals:
                    self.symbols_to_keep.add(var.symbol)
                    return node
            elif node.kind =='scope':
                scope = node_to_scope(node)
                self.scopes.append(scope.name)
                return node
            elif node.kind == 'upscope':
                self.scopes.pop()
                return node
            elif node.kind == 'dumpvars':
                return self.filter_dumpvars(node)
            # TODO move this check to the parser
            elif node.kind not in vcd_keywords:
                raise Exception('unknown node kind {}'.format(node))
            else:
                return node
        elif type(node) == ValueChange:
            if node.id in self.symbols_to_keep:
                return node
            return None
        else:
            return node
    
    def filter_dumpvars(self, node):
        if type(node) != Statement:
            raise Exception('dumpvars must be a Statement, not {}'.format(node))
        if node.kind != 'dumpvars':
            raise Exception('{} is not a dumpvars statement'.format(node))
        data = list()
        i = 0
        length = len(node.data)
        while i < length:
            l = node.data[i]
            if not l[0] in self.valid_values:
                l = node.data[i] + ' ' + node.data[i+1]
                i += 1
            value_change = parse_value_change(l)
            if value_change.id in self.symbols_to_keep:
                data.append(l)
            i += 1
        return Statement('dumpvars', data)

    
    def __iter__(self):
        return self

    def __next__(self):
        r = None
        while r is None:
            r = self.filter(self.parser.__next__())
        return r


def main(vcd_file, signal_file):
    from vcd.parser import Parser
    from vcd.emitter import Emitter

    signals = None
    output_filename = None

    with open(signal_file, 'r') as f:
        signals = f.readlines()
    
    signals = [x.strip() for x in signals]

    with open(vcd_file, 'r') as f:
        parser = Parser(f)
        filter_ = SignalFilter(parser, signals)
        emitter = Emitter(filter_)

        vcd_filename = vcd_file[:-4]  # remove the .vcd
        output_filename = vcd_filename + '_filtered.vcd'
        with open(output_filename, 'w') as g:
            for line in emitter:
                g.write(line + '\n')
        
    return output_filename

if __name__ == '__main__':
    import argparse
    import os.path
    import sys

    from vcd.parser import Parser
    from vcd.emitter import Emitter

    project_root = os.path.abspath(os.path.dirname(sys.argv[0]) + '/../..')
    gtkwave_scripts_dir = os.path.join(project_root, 'scripts/gtkwave')

    parser = argparse.ArgumentParser()
    parser.add_argument('vcd_file')
    parser.add_argument('signal_file')
    args = parser.parse_args()

    main(args.vcd_file, args.signal_file)

    # signals = None

    # with open(args.signals_file, 'r') as f:
    #     signals = f.readlines()
    
    # signals = [x.strip() for x in signals]

    # with open(args.vcd_file, 'r') as f:
    #     parser = Parser(f)
    #     filter_ = SignalFilter(parser, signals)
    #     emitter = Emitter(filter_)

    #     vcd_filename = args.vcd_file[:-4]  # remove the .vcd
    #     output_filename = vcd_filename + '_filtered.vcd'
    #     with open(output_filename, 'w') as f:
    #         for line in emitter:
    #             f.write(line + '\n')