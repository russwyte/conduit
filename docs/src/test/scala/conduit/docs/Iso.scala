package conduit.docs

import _root_.conduit.*
import _root_.conduit.Iso.*
import ascent.*
import ascent.dsl.*
import specular.*
import specular.mermoid.Mermoid
import zio.test.*

object IsoPage extends DocSpec:

  case class Box(n: Int) derives Optics

  enum BoxOp extends Action:
    case SetStr(s: String)
    case Inc

  private val asStr: Lens[Box, String] =
    Optics[Box](_.n).imap(_root_.conduit.Iso[Int, String](_.toString, _.toInt))

  private val handler: ActionHandler[Box, Box, Nothing] =
    handle[Box, Box, Nothing](Optics[Box]):
      case BoxOp.Inc =>
        focus(_.n)(update(_ + 1))
      case BoxOp.SetStr(s) =>
        m => zio.ZIO.succeed(ActionResult(asStr.set(m, s)))

  private val round =
    """flowchart LR
      |  Int[Int field] -->|toString| Str[String focus]
      |  Str -->|toInt| Int
      |""".stripMargin

  def doc = page("Iso")(
    md"""
An `Iso[A, B]` is `to` and `from` that are inverses. `import conduit.Iso.*` brings `imap` /
`xmap` on `Lens`: view an `Int` field as a `String` (or any other type) without storing the
viewed type in the model.

Lawfulness is on you. If `from(to(a)) != a`, lens laws on the derived lens fail in the places
the iso loses information.
""",
    section("imap / xmap")(
      md"""
```scala
import conduit.Iso.*

val intToStr = Iso[Int, String](_.toString, _.toInt)
val asStr    = Optics[Box](_.n).imap(intToStr)
// xmap(to, from) is imap(Iso(to, from))
```
""",
      example {
        Mermoid.diagram(round)
      }.assert(ui => assertTrue(ui != null)),
      exampleValue {
        val box = Box(42)
        (asStr.get(box), asStr.set(box, "100").n)
      }.assert { case (shown, stored) =>
        assertTrue(shown == "42", stored == 100)
      },
    ),
    section("Live: store Int, show String")(
      md"""
The model is `Box(n: Int)`. Buttons write through the iso (`SetStr`) or the raw `Int` lens
(`Inc`). Both stay in sync.
""",
      exampleIO {
        for
          (_, ctx) <- DocsRuntime.live(Box(7))(handler)
          n        <- ctx.squawk(_.n)
        yield E.div(
          E.p("stored Int: ", E.strong(n.map(_.toString))),
          E.button(Events.onClick(_ => ctx(BoxOp.Inc)), "Inc"),
          E.button(Events.onClick(_ => ctx(BoxOp.SetStr("0"))), "Set 0"),
          E.button(Events.onClick(_ => ctx(BoxOp.SetStr("42"))), "Set 42"),
        )
      }.interactive,
      exampleZIO {
        for
          c <- Conduit(Box(7))(handler)
          _ <- c(BoxOp.SetStr("10"), BoxOp.Inc)
          _ <- c.run()
          s <- c.currentModel
        yield (s.n, asStr.get(s))
      }.assert { case (n, shown) =>
        assertTrue(n == 11, shown == "11")
      },
    ),
    section("reverse")(
      md"""
`iso.reverse` swaps `to` / `from`. `Iso.id[A]` is the identity. Compose isos yourself if you
need a pipeline; Conduit only ships `imap` on one lens.
""",
      exampleIO {
        Mermoid.diagramInteractive(round, initialWidth = 480)
      }.interactive,
    ),
  )
end IsoPage
