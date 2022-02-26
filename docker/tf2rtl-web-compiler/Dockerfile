FROM azul/zulu-openjdk:11

COPY ./scala-2.12/tf2rtl-assembly-0.1.jar /root/tf2rtl.jar

WORKDIR /root

CMD java -cp tf2rtl.jar tf2rtl.tools.web.CompilerApp
