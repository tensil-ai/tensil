from collections import namedtuple


Statement = namedtuple('Statement', ['kind', 'data'])
Timestep = namedtuple('Timestep', ['time'])
ValueChange = namedtuple('ValueChange', ['id', 'value'])


# TODO figure out how to parse those goofy var statements that don't have a var
# keyword basically need to parse every statement that doesn't have a known
# keyword.


class HeaderParser:
    def __init__(self, vcd_file):
        self.vcd_file = vcd_file
        self.line = ''
        self.line_count = 0
        self.tokens = list()
        self.kind = None
        self.data = list()
        self.in_stmt = False
        self.timestep_seen = None

    def next_statement(self):
        while True:
            for i in range(len(self.tokens)):
                tok = self.tokens[i]
                if tok == '$end':
                    self.in_stmt = False
                    if self.kind is None:
                        raise Exception('failed to parse statement kind at line ' +
                            '{}: {}'.format(self.line_count, self.line))
                    stmt = Statement(self.kind, self.data)
                    self.kind = None
                    self.data = list()
                    self.tokens = self.tokens[i+1:]
                    return stmt
                elif tok[0] == '$':
                    # TODO turns out $ can be used as an ascii character in the symbol ids. Fuck!
                    if self.in_stmt:
                        self.data.append(tok)
                    else:
                        self.kind = tok[1:]
                        self.in_stmt = True
                elif tok[0] == '#' and not self.in_stmt:
                    # its a timestep
                    # stop this parser and start the value change parser
                    self.timestep_seen = int(tok[1:])
                    raise StopIteration
                else:
                    if self.in_stmt:
                        self.data.append(tok)
            # if we come out here it means the $end is on a subsequent line
            self.line = self.vcd_file.readline()
            self.line_count += 1
            self.tokens = self.line.split()
            if self.line == '':
                # reached EOF
                if self.in_stmt:
                    if self.kind is None:
                        raise Exception('failed to parse statement kind at line ' +
                            '{}: {}'.format(self.line_count, self.line))
                    return Statement(self.kind, self.data)
                raise StopIteration
        # return self.next_statement()

    def __iter__(self):
        return self

    def __next__(self):
        return self.next_statement()


class ValueChangeParser:
    def __init__(self, vcd_file, initial_timestep):
        self.vcd_file = vcd_file
        self.initial_timestep = initial_timestep
        self.initialized = False
    
    def next_line(self):
        if not self.initialized:
            self.initialized = True
            return Timestep(self.initial_timestep)
        line = self.vcd_file.readline()
        if line == '':
            raise StopIteration
        line = line.strip()
        if line[0] == '#':
            # it's a timestep
            return Timestep(int(line[1:]))
        else:
            return parse_value_change(line)
    
    def __iter__(self):
        return self
    
    def __next__(self):
        return self.next_line()


def parse_value_change(line):
    tokens = line.split()
    if len(tokens) == 1:
        value = tokens[0][0]
        id_ = tokens[0][1:]
        return ValueChange(id_, value)
    if len(tokens) == 2:
        value, id_ = tokens
        return ValueChange(id_, value)
    raise Exception('invalid value ' +
        'change at {}'.format(line))



class Parser:
    def __init__(self, vcd_file):
        self.vcd_file = vcd_file
        self.header_parser = HeaderParser(self.vcd_file)
        self.header_done = False
        self.value_change_parser = None
    
    def next_node(self):
        if not self.header_done:
            try:
                return self.header_parser.__next__()
            except StopIteration:
                self.header_done = True
                self.value_change_parser = ValueChangeParser(self.vcd_file,
                    self.header_parser.timestep_seen)
        return self.value_change_parser.__next__()
        

    def __iter__(self):
        return self
    
    def __next__(self):
        return self.next_node()