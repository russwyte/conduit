import xerial.sbt.Sonatype.sonatypeCentralHost

ThisBuild / scalaVersion := "3.6.3"

usePgpKeyHex("2F64727A87F1BCF42FD307DD8582C4F16659A7D6")

ThisBuild / publishMavenStyle.withRank(KeyRanks.Invisible)    := true
ThisBuild / pomIncludeRepository.withRank(KeyRanks.Invisible) := { _ => false }
ThisBuild / sonatypeCredentialHost                            := sonatypeCentralHost
ThisBuild / publishTo                                         := sonatypePublishToBundle.value
ThisBuild / licenses             := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage             := Some(url("https://github.com/russwyte/conduit"))
ThisBuild / organization         := "io.github.russwyte"
ThisBuild / organizationName     := "russwyte"
ThisBuild / organizationHomepage := Some(url("https://github.com/russwyte"))
ThisBuild / versionScheme        := Some("early-semver")
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/russwyte/conduit"),
    "scm:git@github.com:russwyte/conduit.git",
  )
)
ThisBuild / developers := List(
  Developer(
    id = "russwyte",
    name = "Russ White",
    email = "356303+russwyte@users.noreply.github.com",
    url = url("https://github.com/russwyte"),
  )
)

lazy val core = (projectMatrix in file("core"))
  .settings(
    name        := "conduit",
    description := "A ZIO-based library for building event-driven systems",
    scalacOptions ++= Seq(
      "-deprecation",
      "-Wunused:all",
      "-feature",
    ),
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio"               % "2.1.16",
      "dev.zio" %%% "zio-streams"       % "2.1.16",
      "dev.zio" %%% "zio-test"          % "2.1.16" % Test,
      "dev.zio"  %% "zio-test-sbt"      % "2.1.16" % Test,
      "dev.zio"  %% "zio-test-magnolia" % "2.1.16" % Test,
    ),
  )
  .jvmPlatform(scalaVersions = Seq("3.6.3"))
  .jsPlatform(
    scalaVersions = Seq("3.6.3"),
    Seq(
      scalaJSUseMainModuleInitializer := true,
      libraryDependencies ++= Seq(
        "io.github.cquiroz" %%% "scala-java-time"      % "2.6.0",
        "io.github.cquiroz" %%% "scala-java-time-tzdb" % "2.6.0",
      ),
    ),
  )
  .nativePlatform(
    scalaVersions = Seq("3.6.3"),
    Seq(
      libraryDependencies ++= Seq(
        "io.github.cquiroz" %%% "scala-java-time"      % "2.6.0",
        "io.github.cquiroz" %%% "scala-java-time-tzdb" % "2.6.0",
      )
    ),
  )
