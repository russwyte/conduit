package conduit.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.site.*
import zio.*

import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/** Docs-as-tests site builder (Test classpath; `docs/specularSite`). */
object BuildSite extends DocsSite:

  def pages = DocPages.all

  override def site: SiteModel =
    val m       = meta
    val branded = EarlyEffectTheme.brand(super.site)
    branded.copy(
      clientScript = Some("assets/client.js"),
      summaryMarkdown = Some(
        """**Conduit** is unidirectional state for Scala 3 and ZIO 2: actions, lensed handlers,
typed effects, and listeners that skip work when FastEq says nothing changed.

Cross-built for JVM, Scala.js, and Scala Native. These pages are DocSpecs: examples assert
under zio-test, diagrams are real mermoid renders, and the live widgets are a Conduit plus
ascent `Ctx` (docs-only; the published `conduit` artifact does not depend on ascent).
"""
      ),
      installSnippets = Vector(
        ArtifactKind.defaultInstall(m, ArtifactKind.Library),
        CodeSnippet(
          "Scala.js / Native",
          s"""libraryDependencies += "${m.organization}" %%% "${m.name}" % "${m.version}"""",
        ),
      ),
      brand = Some(
        Brand(
          name = m.title.getOrElse("conduit"),
          links = Vector(EarlyEffectTheme.github("https://github.com/early-effect/conduit")),
        )
      ),
    )
  end site

  override def layers: ZLayer[Any, Nothing, SiteBuilder] =
    EarlyEffectTheme.layers

  override def afterBuild(out: Path, result: SiteOutput): Task[Unit] =
    val _ = result
    EarlyEffectTheme.writeLogo(out) *> copyClientBundle(out)

  private def copyClientBundle(out: Path): Task[Unit] =
    ZIO.attempt {
      val dest = out.resolve("assets/client.js")
      val src = findClientJs.getOrElse {
        throw new RuntimeException(
          "JS client not linked; run docs/specularSite (or docsJS/fastLinkJS) first. " +
            s"Looked for marker ${clientJsMarker} and under ${repoRoot.resolve("target/out")}"
        )
      }
      Files.createDirectories(dest.getParent)
      Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
      ()
    }

  private def clientJsMarker: Path =
    repoRoot.resolve("target/specular-client-js.path")

  private def findClientJs: Option[Path] =
    readMarker.orElse(walkTargetOut)

  private def readMarker: Option[Path] =
    val marker = clientJsMarker
    if !Files.isRegularFile(marker) then None
    else
      val line = Files.readString(marker).nn.trim
      if line.isEmpty then None
      else
        val path = Paths.get(line)
        Option.when(Files.isRegularFile(path))(path)
  end readMarker

  private def walkTargetOut: Option[Path] =
    val outRoot = repoRoot.resolve("target/out")
    if !Files.isDirectory(outRoot) then None
    else
      val stream = Files.walk(outRoot)
      try
        val found = stream
          .filter { p =>
            val s = p.toString.replace('\\', '/')
            s.endsWith("conduit-docs-fastopt/main.js")
          }
          .findFirst()
        if found.isPresent then Some(found.get.nn) else None
      finally stream.close()
    end if
  end walkTargetOut

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath.nn)(p => Option(p.getParent).orNull)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(Paths.get("").toAbsolutePath.nn)
end BuildSite
