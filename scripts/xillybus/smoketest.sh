#!/bin/bash

usage () {
  echo "Usage: $0"
  echo ""
  echo "  Writes to /dev/xillybus_write_8 and reads from /dev/xillbus_read_8,"
  echo "  checking that the data read matches the data written. This test"
  echo "  assumes that there is a FIFO loopback connecting write_8 to read_8."
}

echo "Setting up read..."
cat /dev/xillybus_read_8 > smoke_test.out &
read_pid=$!
sleep 1

echo "Writing..."
echo "testing 1234" > /dev/xillybus_write_8
echo "testing 1234" > smoke_test.expected

echo "Cleaning up read..."
kill $read_pid

# exit if the following command fails
set -e

echo "Comparing data..."
diff smoke_test.out smoke_test.expected

echo " DONE"
