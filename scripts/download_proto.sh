#!/usr/bin/env bash

set -e  # stop on error


# proto_files is a list of .proto that are found on Tensorflow Github under
# tensorflow/tensorflow/core/framework. If new .proto files are added there,
# this will need to be updated.

pushd $project_root/tf_proto
    echo "Downloading .proto files from Tensorflow Github..."
    while read p; do
        echo "$p"
        curl -O https://raw.githubusercontent.com/tensorflow/tensorflow/master/tensorflow/core/framework/$p.proto > /dev/null 2>&1
    done < $project_root/scripts/proto_files

    echo "Rewriting import statements..."
    $project_root/scripts/rewrite_imports.py

    echo "Done."
popd > /dev/null
