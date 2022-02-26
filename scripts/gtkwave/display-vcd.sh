#!/bin/bash

# This script generates a GTKW file from the given VCD file for a better GTKWave
# viewing experience. It runs a pre-processing script and then launches GTKWave's
# GUI.
#
# Adapted from https://github.com/IBM/hdl-tools/blob/master/scripts/gtkwave-helper
# by Schuyler Eldridge under Apache v2 license terms, copyright IBM 2017.

usage() {
  echo "Usage: $0 [-h] vcd_file"
  echo "  Adds all user-defined signals to the GTKWave display and prettifies them"
}

if [ $# -ne 1 ]; then
  usage
  exit 1
fi

if [[ "$1" == "--help" || "$1" == "-h" ]]; then
  usage
  exit 0
fi


gtkwave_scripts_dir=$(dirname "$0")

vcd_file="$1"
vcd_dir=$(dirname "${vcd_file}")
vcd_name=$(basename "${vcd_file}")
module_name="${vcd_name%.*}"
gtkw_temp_file=${vcd_dir}/${module_name}.gtkw.tmp
gtkw_file=${vcd_dir}/${module_name}.gtkw

is_macos=false
if [[ "$OSTYPE" == "darwin"* ]]; then
  is_macos=true
fi

echo "Preprocessing ${vcd_file} to generate ${gtkw_file}"

if [ $is_macos = true ]; then
  gtkwave -S "${gtkwave_scripts_dir}"/addWavesRecursive.tcl "${vcd_file}" -O "${gtkw_temp_file}"
else
  gtkwave --rcvar "splash_disable on" -S "${gtkwave_scripts_dir}"/addWavesRecursive.tcl "${vcd_file}" -O "${gtkw_temp_file}"
fi

awk -f "${gtkwave_scripts_dir}"/take-after-start-marker.awk "${gtkw_temp_file}" > "${gtkw_file}"
rm "${gtkw_temp_file}"

echo "Launching GTKWave..."

if [ $is_macos = true ]; then
  gtkwave "${vcd_file}" "${gtkw_file}" -O /dev/null &
else
  gtkwave --rcvar "splash_disable on" "${vcd_file}" "${gtkw_file}" -O /dev/null &
fi

echo " DONE"
