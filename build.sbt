name := "ExcelTranslate"

version := "0.1"
maintainer := "carsten.saager@sapiens.org"
scalaVersion := "2.13.0"

scalacOptions += "-deprecation"
scalacOptions += "-unchecked"

import NativePackagerHelper._

enablePlugins(JavaAppPackaging, DockerPlugin)

libraryDependencies += "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2"
// https://mvnrepository.com/artifact/org.apache.poi/poi-ooxml
libraryDependencies += "org.apache.poi" % "poi-ooxml" % "4.1.0"


// https://mvnrepository.com/artifact/org.scala-lang.modules/scala-xml
libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.2.0"

// https://mvnrepository.com/artifact/org.apache.activemq/activemq-client
libraryDependencies += "org.apache.activemq" % "activemq-client" % "5.15.9"

// https://mvnrepository.com/artifact/com.typesafe/config
libraryDependencies += "com.typesafe" % "config" % "1.3.4"

// https://mvnrepository.com/artifact/org.apache.activemq/activemq-broker
libraryDependencies += "org.apache.activemq" % "activemq-broker" % "5.15.9"

// https://mvnrepository.com/artifact/com.typesafe.akka/akka-actor
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.5.23"

// https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"

packageName in Docker := "ExcelServer"

dockerBaseImage := "openjdk:jre-alpine"

dockerExposedPorts:=Seq(61616)

dockerEntrypoint:=Seq("/opt/docker/bin/service")

