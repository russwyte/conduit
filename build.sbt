import xerial.sbt.Sonatype.sonatypeCentralHost
usePgpKeyHex("2F64727A87F1BCF42FD307DD8582C4F16659A7D6")
val scala3Version = "3.6.3"
scalaVersion := scala3Version

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
val zioVersion = "2.1.16"
// spellchecker: disable
val timeWrappers = Seq(
  "io.github.cquiroz" %% "scala-java-time"      % "2.6.0",
  "io.github.cquiroz" %% "scala-java-time-tzdb" % "2.6.0",
)
// spellchecker: enable
val scalaVersions = Seq(scala3Version)

lazy val root = (project in file("."))
  .aggregate(core.projectRefs ++ example.projectRefs: _*)
  .settings(
    publish / skip := true,
    test / skip    := true,
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
      "dev.zio" %%% "zio"          % zioVersion,
      "dev.zio" %%% "zio-streams"  % zioVersion,
      "dev.zio" %%% "zio-test"     % zioVersion % Test,
      "dev.zio" %%% "zio-test-sbt" % zioVersion % Test,
    ),
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(
    scalaVersions = scalaVersions,
    Seq(
      scalaJSUseMainModuleInitializer := true,
      libraryDependencies ++= timeWrappers,
    ),
  )
  .nativePlatform(
    scalaVersions = scalaVersions,
    Seq(
      libraryDependencies ++= timeWrappers
    ),
  )

lazy val example = (projectMatrix in file("example"))
  .dependsOn(core)
  .settings(
    name           := "conduit-example",
    publish / skip := true,
    test / skip    := true,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(scalaVersions = scalaVersions)
  .nativePlatform(scalaVersions = scalaVersions)
