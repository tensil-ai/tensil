#!/usr/bin/env python3

import argparse
import glob
import os.path
import subprocess
import sys


def main():
    project_root = os.path.abspath(os.path.dirname(sys.argv[0]) + '/../..')
    gtkwave_scripts_dir = os.path.join(project_root, 'scripts/gtkwave')

    parser = argparse.ArgumentParser()
    parser.add_argument('--test_run_dir',
                        default=os.path.join(project_root, 'test_run_dir'),
                        help='tree in which to search for VCD files')
    parser.add_argument('--signal_filter_file', default=None,
        help='file containing a signal allow-list separated by newlines')
    args = parser.parse_args()

    latest = None
    files = glob.iglob(args.test_run_dir + '/**/*.vcd', recursive=True)
    for file in files:
        if latest is None or os.path.getmtime(file) > os.path.getmtime(latest):
            latest = file

    if latest is None:
        print("No VCD files found in {}".format(args.test_run_dir))
        sys.exit(1)

    print('Latest VCD file is {}'.format(latest))

    original_vcd_file = latest
    if args.signal_filter_file is not None:
        sys.path.append(gtkwave_scripts_dir)

        from vcd.filter import main as filter_main

        signal_file = args.signal_filter_file

        if not os.path.isabs(args.signal_filter_file):
            # signal_file = os.path.abspath(os.path.join(os.curdir, args.signal_filter_file))
            signal_file = os.path.abspath(args.signal_filter_file)

        print('Filtering signals through allow-list ' +
            'specified in {}'.format(signal_file))
        latest = filter_main(latest, signal_file)

        # touch original vcd file to ensure that it is still the newest
        # instead of the newly created filtered VCD file
        subprocess.call(['touch', original_vcd_file])

    subprocess.call([os.path.join(gtkwave_scripts_dir, 'display-vcd.sh'),
                     latest])


if __name__ == '__main__':
    main()
