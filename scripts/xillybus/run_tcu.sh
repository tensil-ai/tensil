#!/bin/bash

program="$1"
weights="$2"
inputs="$3"

usage () {
  echo "Usage: $0 program weights inputs"
  echo ""
  echo "  Runs <program> on the TCU with specified <weights> and <input>."
}

exit_on_fail () {
  return_code=$1
  if [ $return_code -ne 1 ]; then
    kill $output_pid
    exit $return_code
  fi
}

if [ -z "${program}" ] || [ -z "${weights}" ] || [ -z "${inputs}" ]; then
  usage
  exit 1
fi

echo "Setting up output read..."
cat /dev/xillybus_output_32 > output.tensil &
output_pid=$!

echo "Writing in program..."
cat $program > /dev/xillybus_program_32
exit_on_fail $?

echo "Writing in weights..."
cat $weights > /dev/xillybus_weights_32
exit_on_fail $?

echo "Writing in input..."
cat $inputs > /dev/xillybus_input_32
exit_on_fail $?

sleep 2
kill $output_pid

echo "Output is at output.tensil"
echo " DONE"
