#!/usr/bin/env bash

set -e  # stop on error


# First you need to have downloaded and extracted scalapbc into the project root from
# https://github.com/scalapb/ScalaPB/releases/download/v0.7.4/scalapbc-0.7.4.zip

pushd $project_root > /dev/null
    ./scalapbc-0.7.4/bin/scalapbc ./tf_proto/* --scala_out=./src/main/scala --proto_path=./tf_proto
popd > /dev/null