TensorFlow To RTL (tf2rtl)
==========================

[![Build status](https://badge.buildkite.com/e44156260ed9775ea72699e45cb394526ea7db24b09c4059aa.svg?branch=master)](https://buildkite.com/tensil/build)

## Setup

1. [Install](https://www.scala-sbt.org/download.html) `sbt` (scala build tool).
2. Get IntelliJ and install the Scala plugin. See the [IntelliJ Installation Guide](https://github.com/ucb-bar/chisel-template/wiki/IntelliJ-Installation-Guide) for how to install it.
3. Clone this repo. In IntelliJ > Import Project from sbt. Point it at the build.sbt file. 
4. Download JDK 11 from Azul https://www.azul.com/downloads/?version=java-11-lts&package=jdk
5. In IntelliJ > Settings > Build, Execution, Deployment > Build Tools > sbt, set the Java runtime (JRE) to Azul JDK 11.
6. Launch sbt by running `sbt` in a terminal or by opening the sbt shell in IntelliJ
7. In the sbt console, run `compile` and then `test:compile`
8. *\[optional\]* Do the Chisel [tutorials](https://github.com/ucb-bar/chisel-tutorial)


## Run

### Building for Zynq

1. Go to `tf2rtl/zynq/tcu/Top.scala` and adjust the design parameters as desired. 
2. `sbt> runMain tf2rtl.zynq.tcu.Top`

Two files will be generated in the `build` directory: `Top.v` and `bram_<width>x<depth>.v`. With those two files, then follow the instructions in the [tcu-pynq](https://github.com/tensil-ai/tcu-pynq) project to implement on the Zynq platform.


### Compiling a model

1. Add the model to the `tf_models` directory under the project root.
2. Go to `tf2rt/tools/Main.scala` and adjust the model filename and accelerator design parameters as desired.
3. `sbt> runMain tf2rtl.tools.Main`

Several artifacts will be generated in the `build` directory, including `program.tensil` and `weights.tensil`. How to use these artifacts depends on the specific platform. For Zynq, see the [tcu-pynq](https://github.com/tensil-ai/tcu-pynq) project.

## Web compiler

### Authenticate with AWS

    $ aws ecr get-login-password --region us-west-1 | docker login --username AWS --password-stdin 550752563960.dkr.ecr.us-west-1.amazonaws.com

### Build and push

    $ sbt assembly
    $ docker-compose build
    $ docker-compose push

### Creating compiler job (SBT console)

User ID, job ID and model name derived from model object name in S3 uploads bucket. Thus is an example for `f21fbe299f890b135342661430e556f21c640e89c97f1a1dfaf2db2de0cbca7c/cnn_mnist_pb_2022_02_11_23_26_34/cnn-mnist.pb`:

    tf2rtl.tools.web.CompilerTask.submitGrid("f21fbe299f890b135342661430e556f21c640e89c97f1a1dfaf2db2de0cbca7c", "cnn_mnist_pb_2022_02_11_23_26_34", "cnn_mnist_pb", 0, "f21fbe299f890b135342661430e556f21c640e89c97f1a1dfaf2db2de0cbca7c/cnn_mnist_pb_2022_02_11_23_26_34/cnn-mnist.pb")

### Launch compiler task

    $ aws ecs run-task --region us-west-1 --cluster tf2rtl-web-d6de55d --network-configuration "awsvpcConfiguration={subnets=[subnet-0ec7bfe08a86df91b,subnet-045397e5cc0c1eb48],securityGroups=[sg-0c1ade67f5e17fb6f],assignPublicIp=DISABLED}" --task-definition tf2rtl-web-compiler:2 --count 1 --launch-type FARGATE