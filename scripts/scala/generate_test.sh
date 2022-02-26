#!/bin/bash

project_root=$(dirname "$0")/../..

usage() {
  echo "Usage: $0 [-h] chisel_module"
  echo "  Generates a basic Chisel flat spec for the specified module"
  echo ""
  echo "  E.g. \`$0 tf2rtl.util.ChangeDetector\` will create a file at"
  echo "  src/test/scala/tf2rtl/util/ChangeDetectorSpec.scala containing"
  echo "  a Chisel flat spec skeleton"
}

if [ $# -ne 1 ]; then
  usage
  exit 1
fi

if [[ "$1" == "--help" || "$1" == "-h" ]]; then
  usage
  exit 0
fi

module="$1"
package_name="${module%.*}"  # trim off everything after the last period
module_name="${module##*.}"  # trim off everything before the last period

# convert module name into path by translating `.` to `/`
test_file_stem=$(echo "$1" | tr "." "/")
test_file="${project_root}/src/test/scala/${test_file_stem}Spec.scala"

echo "Generating ${test_file}"

cat > "${test_file}" <<- END

package ${package_name}

import chisel3._
import chisel3.iotesters.ChiselFlatSpec
import tf2rtl.{FixedDriver, FixedPeekPokeTester}

class ${module_name}Spec extends ChiselFlatSpec {
  behavior of "${module_name}"

  it should "work" in {
    FixedDriver(() => new ${module_name}(/* TODO Fill in module arguments */)) {
      dut => new FixedPeekPokeTester(dut) {
        // TODO Fill in test skeleton
        expect(false, "test not implemented")
      }
    } should be (true)
  }
}

END

echo " DONE"
