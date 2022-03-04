FROM azul/zulu-openjdk:11

RUN mkdir -p /opt/tensil
RUN mkdir -p /work
RUN mkdir -p /demo/arch
RUN mkdir -p /demo/models

RUN apt-get update && apt-get -y install wget
RUN wget https://github.com/tensil-ai/tensil-models/archive/main.tar.gz && tar xf main.tar.gz
RUN mv tensil-models-main/resnet20v2_cifar.* /demo/models/
RUN mv tensil-models-main/resnet50v2_imagenet.* /demo/models/
RUN mv tensil-models-main/yolov4_tiny_192.* /demo/models/
RUN rm -rf main.tar.gz tensil-models-main

COPY ./arch/* /demo/arch/

COPY ./out/rtl/assembly.dest/out.jar /opt/tensil/rtl.jar
COPY ./out/tools/assembly.dest/out.jar /opt/tensil/tools.jar
COPY ./docker/bin/* /usr/bin/
