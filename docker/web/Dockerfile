FROM azul/zulu-openjdk:11 as build

RUN apt-get update && apt-get -y install curl

WORKDIR /work
COPY mill build.sc ./
ADD common ./common
ADD tools ./tools
ADD web ./web

RUN ls -la
RUN ./mill 'web.assembly'

FROM azul/zulu-openjdk:11

RUN mkdir -p /opt/tensil
COPY --from=build /work/out/web/assembly.dest/out.jar /opt/tensil/web.jar

CMD java -jar /opt/tensil/web.jar