import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*

MyVersions.settings

scalaVersion := MyVersions.scala

val scala3Version: String = MyVersions.scala
val scalaVersions         = Seq(scala3Version)

// sbt 2.x scopes bare build.sbt settings to ThisBuild.
organization         := "rocks.earlyeffect"
organizationName     := "Early Effect"
organizationHomepage := Some(url("https://www.earlyeffect.rocks"))
licenses             := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
homepage             := Some(url("https://github.com/early-effect/conduit"))
scmInfo              := Some(
  ScmInfo(
    url("https://github.com/early-effect/conduit"),
    "scm:git@github.com:early-effect/conduit.git",
  )
)
developers := List(
  Developer(
    id = "russwyte",
    name = "Russ White",
    email = "356303+russwyte@users.noreply.github.com",
    url = url("https://github.com/russwyte"),
  )
)
versionScheme := Some("early-semver")

dependencyOverrides += "org.scala-lang" %% "scala3-library" % scalaVersion.value

publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
publishMavenStyle    := true
pomIncludeRepository := { _ => false }

// CI-only publishing: the signing key hex comes from the PGP_KEY_HEX env var, set by
// the shared early-effect org secret in the generated release job. There is no real key
// in this file: the "MISSING_KEY_HEX" sentinel keeps the build loadable for local
// compile/test but makes signing fail loudly if anyone tries to publish off-CI.
usePgpKeyHex(sys.env.getOrElse("PGP_KEY_HEX", "MISSING_KEY_HEX"))

// sbt 2.x defaults eviction to a strict scheme. Native toolchain forces test-interface 0.5.12,
// while zio-test-sbt's Native build still pins 0.5.10 — both 0.5.x and binary-compatible.
libraryDependencySchemes +=
  "org.scala-native" % "test-interface_native0.5_3" % "early-semver"

// zipx CI: builtin fmt / workflow-check / advisories / test (testFull) run in parallel, then Central + Pages.
zipxJavaVersion      := JdkVersion("25")
zipxWorkflowDispatch := true
zipxEnv              := Map(
  "JAVA_OPTS" -> EnvValue.plain(
    "-Xms2048M -Xmx2048M -Xss6M -XX:ReservedCodeCacheSize=256M -Dfile.encoding=UTF-8"
  )
)
zipxCapabilities ++= {
  val upstream = JobCondition.repositoryIs("early-effect/conduit")
  Seq(
    ZipxCentral.release.withCondition(upstream),
    ZipxDocs.pages().andCondition(upstream),
  )
}

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-Wunused:all",
    "-feature",
  )
)

lazy val publishSettings = Seq(
  publishMavenStyle    := true,
  pomIncludeRepository := { _ => false },
)

val docsDogfoodSchemes = Seq(
  "io.github.russwyte" % "conduit_3"      % "always",
  "io.github.russwyte" % "conduit_sjs1_3" % "always",
  "rocks.earlyeffect"  % "conduit_3"      % "always",
  "rocks.earlyeffect"  % "conduit_sjs1_3" % "always",
)

lazy val specularPreview =
  taskKey[Unit]("Build specularSite then serve with sbt-reload (prefer alias: docsPreview)")

lazy val root = (project in file("."))
  .aggregate(core.projectRefs ++ example.projectRefs ++ docs.projectRefs*)
  .settings(
    name           := "conduit-root",
    publish / skip := true,
    test / skip    := true,
  )

lazy val core = (projectMatrix in file("core"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name        := "conduit",
    description := "A ZIO-based library for building event-driven systems",
  )
  .settings(MyVersions.zioLib)
  .settings(MyVersions.zioTests)
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(
    scalaVersions = scalaVersions,
    Seq(
      scalaJSUseMainModuleInitializer := true,
    ) ++ MyVersions.javaTime,
  )
  .nativePlatform(
    scalaVersions = scalaVersions,
    MyVersions.javaTime,
  )

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

lazy val docs = (projectMatrix in file("docs"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name            := "conduit-docs",
    publish / skip  := true,
    publishArtifact := false,
    zipxPublish     := Some(false),
    scalacOptions += "-language:implicitConversions",
    libraryDependencySchemes ++= docsDogfoodSchemes,
  )
  .jvmPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.enablePlugins(SpecularPlugin)
        .settings(MyVersions.docsJvm)
        .settings(
          Test / mainClass       := Some("specular.site.DocsServe"),
          Test / run / mainClass := (Test / mainClass).value,
          Test / runReloadArgs   := Seq(specularPort.value.toString),
          Test / run / javaOptions ++= {
            val dir = specularSiteDirectory.value.getAbsolutePath
            Seq(
              s"-Dspecular.site.dir=$dir",
              s"-Dspecular.site.port=${specularPort.value}",
            )
          },
          specularBuildMain        := "conduit.docs.BuildSite",
          specularMetaProject      := Some(LocalProject("core")),
          specularArtifactKind     := "library",
          specularSiteDirectory    := (ThisBuild / baseDirectory).value / "target" / "site",
          specularDisplayVersion   := stripCi,
          specularJsLink := Def
            .uncached(Def.task {
              (LocalProject("docsJS") / Compile / fastLinkJS).value
              val outDir = (LocalProject("docsJS") / Compile / fastLinkJSOutput).value
              val mainJs = outDir / "main.js"
              if (!mainJs.exists)
                sys.error(
                  s"Expected $mainJs after fastLinkJS; directory contains: " +
                    Option(outDir.list).toSeq.flatten.mkString(", ")
                )
              val marker = (ThisBuild / baseDirectory).value / "target" / "specular-client-js.path"
              IO.write(marker, mainJs.getAbsolutePath)
            })
            .value,
          specularPreview := Def
            .uncached(Def.task {
              specularSite.value
              (Test / runReload).value
            })
            .value,
        ),
  )
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.settings(MyVersions.docsJs)
        .settings(
        Compile / unmanagedSources ++= {
          val dir  = (ThisBuild / baseDirectory).value / "docs" / "src" / "test" / "scala" / "conduit" / "docs"
          val skip = Set("BuildSite.scala", "DocsSuites.scala", "InteractiveContractSpec.scala")
          (dir * "*.scala").get().filterNot(f => skip.contains(f.getName))
        },
        scalaJSUseMainModuleInitializer := true,
        scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
        Compile / mainClass             := Some("conduit.docs.ClientMain"),
        Test / skip                     := true,
        Test / sources                  := Nil,
      ),
  )

addCommandAlias("docsPreview", "~docs/specularPreview")
