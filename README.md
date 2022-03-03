Tensil
==========================

[![Build status](https://badge.buildkite.com/e44156260ed9775ea72699e45cb394526ea7db24b09c4059aa.svg?branch=master)](https://buildkite.com/tensil/build)

## Tensil toolchain flow

![Flow](/doc/flow.png)

## Setup

1. Pull and run [Tensil docker container](https://hub.docker.com/repository/docker/tensilai/tensil) (see below);
2. Clone [Tensil models repo](https://github.com/tensil-ai/tensil-models) on the same level with this repo (optional - demo only);
3. Download and install [Xilinx Vitis or Vivado](https://www.xilinx.com/support/download.html);
4. Download and install [Xilinx PYNQ](http://www.pynq.io/board.html) for your FPGA development platform;
5. Clone [Tensil PYNQ driver](https://github.com/tensil-ai/tensil-drivers) to `/home/xilinx` on your FPGA development platform.

## Pull and run docker container

    $ docker pull tensilai/tensil:latest
    $ docker run -v $(pwd)/..:/workspace -w /workspace -it tensilai/tensil:latest bash

## Compile AI/ML model

Compile AI/ML model (ResNet20 v2 CIFAR) for specific TCU architecture and FPGA development platform ([Avnet Ultra96 v2](https://www.avnet.com/wps/portal/us/products/avnet-boards/avnet-board-families/ultra96-v2/)).

#### From ONNX

    $ tensil compile -a ./tensil/arch/ultra96v2.tarch -m ./tensil-models/resnet20v2_cifar.onnx -o "Identity:0"

#### From frozen TensorFlow graph

    $ tensil compile -a ./tensil/arch/ultra96v2.tarch -m ./tensil-models/resnet20v2_cifar.pb -o "Identity"

#### Other ML frameworks are supported by convering to ONNX

- [TensorFlow and Tflite](https://github.com/onnx/tensorflow-onnx/blob/master/README.md)
- [Pytorch](https://pytorch.org/docs/stable/onnx.html)
- [Other](https://onnx.ai/supported-tools.html)


## Make Verilog RTL

Make Verilog RTL for specific TCU architecture and FPGA development platform (Ultra96 v2) and 128-bit AXI interface to DDR memory.

    $ tensil rtl -a ./tensil/arch/ultra96v2.tarch -d 128

## Create Vivado design

Create Vivado design for specific FPGA development platform (Ultra96 v2). If this is something you don't know how to do, we can help! Please reach out to us at [contact@tensil.ai](mailto:contact@tensil.ai).

![Ultra96 v2 design](/doc/ultra96v2_design.png)

## Run AI/ML model on FPGA

Use PYNQ and Jupyter notebooks to run AI/ML model on FPGA.

![Resnet on PYNQ](/doc/resnet20_on_pynq.png)

## For maintainers

### Additional setup steps

1. Download and install [OpenJDK 11 from Azul](https://www.azul.com/downloads/?version=java-11-lts&package=jdk);
2. Download and install [Verilator](https://verilator.org/guide/latest/install.html);
3. Clone [Tensil models repo](https://github.com/tensil-ai/tensil-models) on the same level with this repo.

### Build command line tools

    $ ./mill '{rtl,tools}.assembly'

### Run full test suite

    $ ./mill __.test -l org.scalatest.tags.Slow

### Build and push docker image

    $ docker build -t tensilai/tensil .
    $ docker push tensilai/tensil