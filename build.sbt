name := "ExcelTranslate"

version := "0.2"
maintainer := "carsten.saager@sapiens.org"
scalaVersion := "2.13.0"

scalacOptions += "-deprecation"
//scalacOptions += "-unchecked"

import NativePackagerHelper._


enablePlugins(JavaAppPackaging, DockerPlugin)


mainClass in Compile := Some("com.sapiens.exceltranslate.Service")

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

// put here the path to Excel files in the svn repo
lazy val excelDir = "C:/Temp/BL/Excel_formula/Excel/"

//mappings in Universal += file("C:/Temp/BL/Excel_formula/Excel/ETIcalc_vanilla.xls") -> "opt/sheets/ETIcalc_vanilla.xls"

// add the excel files
mappings in Docker ++= contentOf(excelDir) map {
  case (src, dest) => src -> s"opt/docker/$dest"
}
//mappings in Docker += (excelDir +"ETIcalc_vanilla.xls") -> "sheets/ETIcalc_vanilla.xls"

dockerBaseImage := "openjdk:jre-alpine"

daemonUser in Docker := "extrans"

// I think we only need it if the app runs the MQ itself
dockerExposedPorts:=Seq(61616)

dockerEntrypoint:=Seq("/opt/docker/bin/service")

