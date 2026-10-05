name := "cloudwatch-logback-appender"

organization := "com.alexdupre"

version := "3.2"
versionScheme := Some("early-semver")

javacOptions ++= Seq("--release", "11")

crossPaths := false // drop off Scala suffix from artifact names
autoScalaLibrary := false // exclude scala-library from dependencies

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.6.5"

libraryDependencies ++= Seq("cloudwatchlogs", "imds").map(service => "software.amazon.awssdk" % service % "2.55.11")

libraryDependencies ++= Seq(
  "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
  "org.easymock" % "easymock" % "5.7.0" % Test,
)

fork := true

organizationName := "Alex Dupre"
organizationHomepage := Some(url("https://github.com/alexdupre"))

scmInfo := Some(
  ScmInfo(
    url(s"https://github.com/alexdupre/${name.value}"),
    s"scm:git:git@github.com:alexdupre/${name.value}.git"
  )
)

developers := List(
  Developer(
    id = "alexdupre",
    name = "Alex Dupre",
    email = "ale@FreeBSD.org",
    url = url("https://github.com/alexdupre")
  )
)

description := "Appender that publishes logback log entries to AWS CloudWatch"
licenses := Seq("ISC License" -> url("https://opensource.org/license/ISC"))
homepage := Some(url(s"https://github.com/alexdupre/${name.value}"))

// Remove all additional repository other than Maven Central from POM
pomIncludeRepository := { _ => false }
publishMavenStyle := true

publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
