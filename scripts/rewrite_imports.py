#!/usr/bin/env python3


import os
import re


def main():
    ext = '.proto'
    for filename in os.listdir(os.curdir):
        if filename.endswith(ext):
            print('Rewriting', filename)
            with open(filename, 'r+') as file:
                s = file.read()
                ss = re.sub(r'import "tensorflow/core/framework/', 'import "', s)
                file.seek(0)
                file.truncate()
                file.write(ss)




if __name__ == '__main__':
    main()