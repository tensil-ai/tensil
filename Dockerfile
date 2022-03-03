FROM azul/zulu-openjdk:11

RUN mkdir -p /opt/tensil
RUN mkdir -p /work
RUN mkdir -p /demo/arch
RUN mkdir -p /demo/models

COPY ./tensil/arch/* /demo/arch/
COPY ./tensil-models/resnet20v2_cifar.* /demo/models/

COPY ./tensil/out/rtl/assembly.dest/out.jar /opt/tensil/rtl.jar
COPY ./tensil/out/tools/assembly.dest/out.jar /opt/tensil/tools.jar
COPY ./tensil/docker/bin/* /usr/bin/
