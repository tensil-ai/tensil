#!/bin/bash

bitfile="$1"

usage () {
  echo "Usage: $0 bit_file"
  echo ""
  echo "  Loads a bitstream onto the user flash of FPGA 0."
}

if [ -z "${bitfile}" ]; then
  usage
  exit 1
fi

echo "Ensuring that device is mapped in bwtk..."
bwconfig --add=usb

# exit if any of the following commands fails
set -e

echo "Preparing to load bitstream ${bitfile} onto FPGA 0..."

# erase fpga
echo ""
echo "Erasing FPGA..."
bwconfig --dev=0 --erase --type=fpga

# erase user flash
# don't actually need to do this
# bwconfig --dev=0 --erase --type=flash --index=0

# load user flash
echo ""
echo "Loading user flash with provided .bit file..."
bwconfig --dev=0 --load=${bitfile} --type=flash --index=0

# start fpga
echo ""
echo "Starting FPGA..."
bwconfig --dev=0 --start --type=fpga

echo " DONE"
