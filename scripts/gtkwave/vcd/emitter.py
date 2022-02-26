from vcd.parser import Statement, Timestep, ValueChange


class Emitter:
    def __init__(self, iterable):
        self.iterable = iterable
    
    def emit(self, node):
        if type(node) == Statement:
            if node.kind == 'dumpvars':
                return '${}\n{}\n$end'.format(node.kind, '\n'.join(node.data))
            return '${} {} $end'.format(node.kind, ' '.join(node.data))
        elif type(node) == Timestep:
            return '#{}'.format(node.time)
        elif type(node) == ValueChange:
            if len(node.value) == 1:
                return '{}{}'.format(node.value, node.id)
            return '{} {}'.format(node.value, node.id)
        else:
            raise Exception('unknown node type at {}'.format(node))
    
    def __iter__(self):
        return self

    def __next__(self):
        return self.emit(self.iterable.__next__())
