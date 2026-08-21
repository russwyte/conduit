import zipx.*

/** Typed catalog. `zipxDepUpdate` rewrites constructors here. sbt-zipx and sbt-pgp are not rows. */
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.0.6")
  val scala: ScalaVersion = ScalaVersion("3.8.4")

  val zio         = Lib("dev.zio", "zio", "2.1.26")
  val zioStreams  = zio.mod("zio-streams")
  val zioTest     = zio.mod("zio-test").test
  val zioTestSbt  = zio.mod("zio-test-sbt").test

  val scalaJavaTime     = Lib("io.github.cquiroz", "scala-java-time", "2.6.0")
  val scalaJavaTimeTzdb = scalaJavaTime.mod("scala-java-time-tzdb")

  val specular        = Lib("rocks.earlyeffect", "specular-core", "0.14.0")
  val specularZioTest = specular.mod("specular-zio-test").test
  val specularSite    = specular.mod("specular-site").test
  val specularTheme   = specular.mod("early-effect-docs-theme").test
  val specularMermoid = specular.mod("specular-mermoid")

  val ascent        = Lib("rocks.earlyeffect", "ascent-core", "0.4.1")
  val ascentCss     = ascent.mod("ascent-css")
  val ascentHtml    = ascent.mod("ascent-html").test
  val ascentJs      = ascent.mod("ascent-js")
  val ascentConduit = ascent
    .mod("ascent-conduit")
    .excluding(ZipxExclude.org("io.github.russwyte"))

  val scalajs        = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")
  val scalaNative    = Plugin("org.scala-native", "sbt-scala-native", "0.5.12")
  val scalafmt       = Plugin("org.scalameta", "sbt-scalafmt", "2.6.1")
  val scalafix       = Plugin("ch.epfl.scala", "sbt-scalafix", "0.14.7")
  val dynver         = Plugin("com.github.sbt", "sbt-dynver", "5.1.1")
  val specularPlugin = Plugin("rocks.earlyeffect", "sbt-specular", "0.14.0")
  val sbtReload      = Plugin("com.jamesward", "sbt-reload", "0.0.7")

  def zioLib   = library(zio, zioStreams)
  def zioTests = library(zioTest, zioTestSbt)
  def javaTime = library(scalaJavaTime, scalaJavaTimeTzdb)

  def docsJvm = library(
    zioTest,
    zioTestSbt,
    specular.test,
    specularZioTest,
    specularSite,
    specularTheme,
    ascentHtml,
    ascentCss.test,
    ascentConduit.test,
  )

  def docsJs = library(
    zio,
    specular,
    specularMermoid,
    ascentJs,
    ascentCss,
    ascentConduit,
    scalaJavaTime,
    scalaJavaTimeTzdb,
  )
end MyVersions
