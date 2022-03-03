FROM azul/zulu-openjdk:11

RUN mkdir -p /opt/tensil
RUN mkdir -p /workspace

COPY ./out/rtl/assembly.dest/out.jar /opt/tensil/rtl.jar
COPY ./out/tools/assembly.dest/out.jar /opt/tensil/tools.jar
COPY ./docker/* /usr/bin/
