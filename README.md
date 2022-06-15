Tensil
==========================

[![Build status](https://badge.buildkite.com/11c53cfb0931de5a89dfece9634fe3a5f5cefc9525e1162e1a.svg?branch=main)](https://buildkite.com/tensil/tensil)

## Tensil toolchain flow

![Flow](https://s3.us-west-1.amazonaws.com/downloads.tensil.ai/doc/flow.png)

## Tutorials

For in-depth end-to-end instructions check our tutorials.

- [Learn how to combine Tensil and TF-Lite to run YOLO on Ultra96](https://www.tensil.ai/docs/tutorials/yolo-ultra96v2/)
- [Learn Tensil with ResNet and PYNQ Z1](https://www.tensil.ai/docs/tutorials/resnet20-pynqz1/)
- [Learn Tensil with ResNet and Ultra96](https://www.tensil.ai/docs/tutorials/resnet20-ultra96v2/)

## Documentation

For reference documentation see our [website](https://www.tensil.ai/docs/).

## Setup

1. Pull and run [Tensil docker container](https://hub.docker.com/r/tensilai/tensil) (see below);
2. Download and install [Xilinx Vitis or Vivado](https://www.xilinx.com/support/download.html);
3. Download and install [Xilinx PYNQ](http://www.pynq.io/board.html) for your FPGA development platform;
4. Copy Tensil PYNQ driver `drivers/tcu_pynq` to `/home/xilinx/tcu_pynq` on your FPGA development platform.

## Pull and run docker container

```
docker pull tensilai/tensil
docker run -v $(pwd):/work -w /work -it tensilai/tensil bash
```

## Compile AI/ML model

Compile AI/ML model (ResNet20 v2 CIFAR) for specific TCU architecture and FPGA development platform, [PYNQ Z1](https://digilent.com/shop/pynq-z1-python-productivity-for-zynq-7000-arm-fpga-soc/) in this example.

#### From ONNX

```
tensil compile -a /demo/arch/pynqz1.tarch -m /demo/models/resnet20v2_cifar.onnx -o "Identity:0" -s true
```

#### From frozen TensorFlow graph

```
tensil compile -a /demo/arch/pynqz1.tarch -m /demo/models/resnet20v2_cifar.pb -o "Identity" -s true
```

#### Other ML frameworks are supported by converting to ONNX

- [TensorFlow and Tflite](https://github.com/onnx/tensorflow-onnx/blob/master/README.md)
- [Pytorch](https://pytorch.org/docs/stable/onnx.html)
- [Other](https://onnx.ai/supported-tools.html)

## Run bit accurate Tensil emulator

```
tensil emulate -m resnet20v2_cifar_onnx_pynqz1.tmodel -i /demo/models/data/resnet_input_1x32x32x8.csv
```

## Make Verilog RTL

Make Verilog RTL for specific TCU architecture and FPGA development platform, [PYNQ Z1](https://digilent.com/shop/pynq-z1-python-productivity-for-zynq-7000-arm-fpga-soc/) in this example.

```
tensil rtl -a /demo/arch/pynqz1.tarch -s true
```

## Create Vivado design

Create Vivado design for specific FPGA development platform. We include detailed steps in our [PYNQ Z1 tutorial](https://www.tensil.ai/docs/tutorials/resnet20-pynqz1/). If you get stuck, we can help! Please reach out to us at [contact@tensil.ai](mailto:contact@tensil.ai) or in [Discord](https://discord.gg/TSw34H3PXr).

![PYNQ Z1 design](https://s3.us-west-1.amazonaws.com/downloads.tensil.ai/doc/pynqz1_design.png)

## Run AI/ML model on FPGA

Use PYNQ and Jupyter notebooks to run AI/ML model on FPGA. (See in `notebooks`.)

![Resnet on PYNQ](https://s3.us-west-1.amazonaws.com/downloads.tensil.ai/doc/resnet20_on_pynq.png)

## For maintainers

### Additional setup steps

1. Download and install [OpenJDK 11 from Azul](https://www.azul.com/downloads/?version=java-11-lts&package=jdk);
2. Download and install [Verilator](https://verilator.org/guide/latest/install.html);
3. Download test models:

```
wget https://github.com/tensil-ai/tensil-models/archive/main.tar.gz
tar xf main.tar.gz
mv tensil-models-main models
rm main.tar.gz
```

### Run RTL tool from source code

```
./mill rtl.run -a ./arch/pynqz1.tarch -s true
```

### Run compiler from source code

```
./mill compiler.run -a ./arch/pynqz1.tarch -m ./models/resnet20v2_cifar.onnx -o "Identity:0" -s true
```

### Run emulator from source code

```
./mill emulator.run -m resnet20v2_cifar_onnx_pynqz1.tmodel -i ./models/data/resnet_input_1x32x32x8.csv
```

### Run full test suite

```
./mill __.test -l org.scalatest.tags.Slow
```

To run a single RTL test in, for example, the Accumulator module, and also output a VCD file, do:

```
./mill rtl.test.testOnly tensil.tcu.AccumulatorSpec -- -DwriteVcd=true -z "should accumulate values"
```

### View VCD files

To view the latest VCD file generated:

```
./scripts/gtkwave/display-latest-vcd.py
```

To view a specific VCD file:

```
./scripts/gtkwave/display-vcd.sh <vcd_file>
```

## Get help

- Say hello and ask a question on our [Discord](https://discord.gg/TSw34H3PXr)
- Email us at [support@tensil.ai](mailto:support@tensil.ai)
