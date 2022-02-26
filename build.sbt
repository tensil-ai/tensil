name := "tf2rtl"

version := "0.1"

scalaVersion := "2.12.14"

resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  Resolver.sonatypeRepo("releases")
)

scalacOptions ++= Seq(
  "-encoding",
  "utf8",
  "-Xsource:2.11",
  "-Xfatal-warnings",
  "-unchecked",
  "-deprecation",
  "-feature",
  "-language:reflectiveCalls",
)

libraryDependencies += "edu.berkeley.cs" %% "chisel3"          % "3.4.3"
libraryDependencies += "edu.berkeley.cs" %% "chisel-iotesters" % "1.5.3"
libraryDependencies += "edu.berkeley.cs" %% "chiseltest"       % "0.3.3"
libraryDependencies += "edu.berkeley.cs" %% "treadle"          % "1.3.3"

// json
libraryDependencies += "com.lihaoyi" %% "upickle" % "1.3.8"

// ScalaPB
Compile / PB.targets := Seq(
  scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
)
libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
)

// AWS
libraryDependencies ++= Seq(
  "com.github.seratch" %% "awscala-s3"       % "0.8.5",
  "com.github.seratch" %% "awscala-sqs"      % "0.8.5",
  "com.github.seratch" %% "awscala-dynamodb" % "0.8.5",
)

// Silence scalapb deprecation warnings
scalacOptions += "-Wconf:src=target/scala-2.12/src_managed/.*:silent"

parallelExecution in ThisBuild := false

addCommandAlias(
  "testCI",
  ";testOnly tf2rtl.* -- -l org.scalatest.tags.Slow -l tf2rtl.tags.Broken -l tf2rtl.tags.Hardware -l tf2rtl.tags.Formal -l tf2rtl.tags.Verilator"
)

addCommandAlias(
  "testArch",
  ";testOnly tf2rtl.util.decoupled.* tf2rtl.axi.* tf2rtl.mem.* tf2rtl.tcu.* -- -l org.scalatest.tags.Slow -l tf2rtl.tags.Broken -l tf2rtl.tags.Hardware -l tf2rtl.tags.Formal -l tf2rtl.tags.Verilator"
)

// notify of slow tests that run longer than 60s
testOptions in Test += Tests.Argument(
  TestFrameworks.ScalaTest,
  "-W",
  "60",
  "60"
)
