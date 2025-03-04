ThisBuild / scalaVersion := "3.6.3"

lazy val core = (projectMatrix in file("core"))
  .settings(
    name := "core",
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
