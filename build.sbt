val scala3Version = "3.8.3"
val zioVersion    = "2.1.26"

// Global settings using ThisBuild scope
ThisBuild / scalaVersion         := scala3Version
ThisBuild / organization         := "io.github.russwyte"
ThisBuild / organizationName     := "russwyte"
ThisBuild / organizationHomepage := Some(url("https://github.com/russwyte"))
ThisBuild / licenses             := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage             := Some(url("https://github.com/russwyte/conduit"))
ThisBuild / scmInfo              := Some(
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
ThisBuild / versionScheme                           := Some("early-semver")
ThisBuild / dependencyOverrides += "org.scala-lang" %% "scala3-library" % scalaVersion.value

usePgpKeyHex("2F64727A87F1BCF42FD307DD8582C4F16659A7D6")

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-Wunused:all",
    "-feature",
  ),
)

// Native sbt 1.11+ Maven Central publishing — no sbt-sonatype plugin required.
lazy val publishSettings = Seq(
  publishMavenStyle    := true,
  pomIncludeRepository := { _ => false },
  publishTo            := localStaging.value,
)

val timeWrappers = Seq(
  "io.github.cquiroz" %% "scala-java-time"      % "2.6.0",
  "io.github.cquiroz" %% "scala-java-time-tzdb" % "2.6.0",
)
val scalaVersions = Seq(scala3Version)

// Root project aggregates all modules but is not published
lazy val root = (project in file("."))
  .aggregate(core.projectRefs ++ example.projectRefs: _*)
  .settings(
    name           := "conduit-root",
    publish / skip := true,
    test / skip    := true,
  )

// Core library - the main publishable artifact
lazy val core = (projectMatrix in file("core"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name        := "conduit",
    description := "A ZIO-based library for building event-driven systems",
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

// Example apps - not published
lazy val example = (projectMatrix in file("example"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name           := "conduit-example",
    publish / skip := true,
    test / skip    := true,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(scalaVersions = scalaVersions)
  .nativePlatform(scalaVersions = scalaVersions)

// Runnable docs (marklit) — JVM-only. Compiles every Scala block in docs/src/main/markdown/
// against the JVM build of `core` and renders the executed output back into Markdown.
lazy val docs = (project in file("docs"))
  .dependsOn(core.jvm(scala3Version))
  .settings(commonSettings)
  .settings(
    name                   := "conduit-docs",
    scalaVersion           := scala3Version,
    publish / skip         := true,
    test / skip            := true,
    marklitSourceDirectory := baseDirectory.value / "src" / "main" / "markdown",
    // Render to <repo-root>/docs-generated/ so committed Markdown links from the
    // README resolve on GitHub and in any plain-Markdown viewer.
    marklitTargetDirectory := (ThisBuild / baseDirectory).value / "docs-generated",
  )
