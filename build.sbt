name := "cloudwatch-logback-appender"

organization := "com.alexdupre"

version := "3.1"

javacOptions ++= Seq("--release", "11")

crossPaths := false // drop off Scala suffix from artifact names
autoScalaLibrary := false // exclude scala-library from dependencies

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.6"

libraryDependencies ++= Seq("cloudwatchlogs", "imds").map(service => "software.amazon.awssdk" % service % "2.25.60")

libraryDependencies ++= Seq(
  "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
  "org.easymock" % "easymock" % "5.2.0" % Test,
)

fork := true

publishTo := sonatypePublishToBundle.value

publishMavenStyle := true

licenses := Seq("ISC License" -> url("https://opensource.org/licenses/ISC"))

sonatypeProjectHosting := Some(xerial.sbt.Sonatype.GitHubHosting("alexdupre", name.value, "Alex Dupre", "ale@FreeBSD.org"))
