FROM azul/zulu-openjdk:11 as build

RUN apt-get update && apt-get -y install curl

WORKDIR /work
COPY mill build.sc ./
ADD common ./common
ADD tools ./tools
ADD rtl ./rtl

RUN ls -la
RUN ./mill '{rtl,tools}.assembly'

FROM azul/zulu-openjdk:11 as models

WORKDIR /work

RUN apt-get update && apt-get -y install wget
RUN wget https://github.com/tensil-ai/tensil-models/archive/main.tar.gz && tar xf main.tar.gz

FROM azul/zulu-openjdk:11

RUN mkdir -p /demo/models
COPY --from=models /work/tensil-models-main/resnet20v2_cifar.* /demo/models/
COPY --from=models /work/tensil-models-main/resnet50v2_imagenet.* /demo/models/
COPY --from=models /work/tensil-models-main/yolov4_tiny_192.* /demo/models/
COPY --from=models /work/tensil-models-main/yolov4_tiny_416.* /demo/models/

RUN mkdir -p /demo/arch
COPY ./arch/* /demo/arch/

RUN mkdir -p /opt/tensil
COPY --from=build /work/out/rtl/assembly.dest/out.jar /opt/tensil/rtl.jar
COPY --from=build /work/out/tools/assembly.dest/out.jar /opt/tensil/tools.jar

COPY ./docker/bin/* /usr/bin/
