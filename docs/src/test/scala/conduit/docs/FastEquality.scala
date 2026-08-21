package conduit.docs

import _root_.conduit.*
import ascent.*
import ascent.dsl.*
import specular.*
import specular.mermoid.Mermoid
import zio.*
import zio.test.*

object FastEquality extends DocSpec:

  case class Counter(value: Int) derives Optics

  enum Op extends Action:
    case Inc
    case NoOp
    case SetSame

  private val handler: ActionHandler[Counter, Int, Nothing] =
    handle[Counter, Int, Nothing](Optics[Counter](_.value)):
      case Op.Inc     => update(_ + 1)
      case Op.NoOp    => noChange
      case Op.SetSame => update(identity)

  case class Versioned(value: Int, version: Long) derives Optics
  object Versioned:
    given FastEq[Versioned] = FastEq.fromVersion(_.version)

  enum VerOp extends Action:
    case Bump
    case QuietSet(v: Int)

  private val verHandler: ActionHandler[Versioned, Versioned, Nothing] =
    handle[Versioned, Versioned, Nothing](Optics[Versioned]):
      case VerOp.Bump        => update(m => m.copy(version = m.version + 1))
      case VerOp.QuietSet(v) => focus(_.value)(updated(v))

  private val gates =
    """flowchart TB
      |  H[Handler] --> D{dirty?}
      |  D -->|clean| Skip[Skip all listeners]
      |  D -->|dirty| M{model FastEq}
      |  M -->|equal| Skip
      |  M -->|changed| S{slice FastEq}
      |  S -->|equal| Quiet[Skip this listener]
      |  S -->|changed| Fire[Callback]
      |""".stripMargin

  def doc = page("FastEq")(
    md"""
`FastEq[A]` is optional. Conduit uses it in two places: **dispatch** (skip every listener if
the model did not change) and **per listener** (skip the callback if that slice did not change).
With no `given`, it is `==` via `FastEq.fromEquals`.
""",
    section("noChange never notifies")(
      md"""
`Op.NoOp` uses `noChange` (`dirty = false`). The listener on `_.value` fires only for `Inc`.
""",
      example {
        Mermoid.diagram(gates)
      }.assert(ui => assertTrue(ui != null)),
      exampleZIO {
        for
          c     <- Conduit(Counter(0))(handler)
          count <- Ref.make(0)
          _     <- c.subscribe(_.value)(_ => count.update(_ + 1))
          _     <- c(Op.NoOp, Op.NoOp, Op.Inc, Op.NoOp)
          _     <- c.run()
          fired <- count.get
        yield fired
      }.assert(n => assertTrue(n == 1)),
    ),
    section("Live: skip vs notify")(
      md"""
**Same** writes `value` to itself (`update(identity)`). Structural `==` is true, so the
listener should not fire. **Inc** does. The badge is how many times the count listener ran.
""",
      exampleIO {
        for
          (c, ctx) <- DocsRuntime.live(Counter(0))(handler)
          fired    <- sq(0)
          _        <- c.subscribe(_.value)(_ => fired.update(_ + 1).unit)
          value    <- ctx.squawk(_.value)
        yield E.div(
          E.p("value ", E.strong(value.map(_.toString)), "  listener fires ", E.strong(fired.map(_.toString))),
          E.button(Events.onClick(_ => ctx(Op.Inc)), "Inc"),
          E.button(Events.onClick(_ => ctx(Op.NoOp)), "NoOp (clean)"),
          E.button(Events.onClick(_ => ctx(Op.SetSame)), "Set same value"),
        )
      }.interactive,
    ),
    section("fromVersion")(
      md"""
`FastEq.fromVersion(_.version)` treats two models as equal when the version field matches,
even if other fields differ. Put the `given` on the model companion; `Conduit.apply` takes
`[M: FastEq]`, so dispatch uses it. `QuietSet` changes `value` without bumping `version`,
so a whole-model listener skips. `Bump` notifies once.
""",
      exampleZIO {
        for
          c     <- Conduit(Versioned(0, 0L))(verHandler)
          fired <- Ref.make(0)
          _     <- c.subscribe(Optics[Versioned])(_ => fired.update(_ + 1))
          _     <- c(VerOp.QuietSet(99), VerOp.Bump)
          _     <- c.run()
          n     <- fired.get
          s     <- c.currentModel
        yield (n, s.value, s.version)
      }.assert { case (n, v, ver) =>
        assertTrue(n == 1, v == 99, ver == 1L)
      },
      exampleIO {
        Mermoid.diagramInteractive(gates, initialWidth = 520)
      }.interactive,
    ),
    section("Factories")(
      md"""
| Factory | When |
| --- | --- |
| `fromEquals` | Explicit `==` (same as the fallback) |
| `instance((a,b) => …)` | Full control |
| `withReferenceEquality(fb)` | `eq` first; lenses keep sibling refs |
| `fromVersion(_.version)` | Monotonic version field |
| `fromHash(_.cachedHash)` | Precomputed hash |
| `withDirtyFlag(_.isDirty, fb)` | Clean-clean short-circuits to equal |
| `derived` / `derives FastEq` | Same as `fromEquals` |
"""
    ),
  )
end FastEquality
