package conduit.docs

import _root_.conduit.*
import ascent.*
import ascent.dsl.*
import specular.*
import specular.mermoid.Mermoid
import zio.*
import zio.test.*

object GettingStarted extends DocSpec:

  case class CounterState(count: Int, history: List[Int]) derives Optics

  enum CounterAction extends Action:
    case Inc
    case Dec
    case Reset
    case Set(v: Int)

  private val countLens = Optics[CounterState](_.count)

  private val countHandler: ActionHandler[CounterState, Int, Nothing] =
    handle[CounterState, Int, Nothing](countLens):
      case CounterAction.Inc    => update(_ + 1)
      case CounterAction.Dec    => update(_ - 1)
      case CounterAction.Reset  => updated(0)
      case CounterAction.Set(v) => updated(v)

  private val focusedHandler: ActionHandler[CounterState, CounterState, Nothing] =
    handle[CounterState, CounterState, Nothing](Optics[CounterState]):
      case CounterAction.Inc    => focus(_.count)(update(_ + 1))
      case CounterAction.Dec    => focus(_.count)(update(_ - 1))
      case CounterAction.Reset  => focus(_.count)(updated(0))
      case CounterAction.Set(v) => focus(_.count)(updated(v))

  private val pieces =
    """flowchart TB
      |  M[Model] --> C[Conduit]
      |  A[Actions] --> Q[Queue]
      |  Q --> H[Handler]
      |  H --> M
      |  C --> Q
      |""".stripMargin

  def doc = page("Getting started")(
    md"""
A complete Conduit app is a model, an action enum, a handler, and a runtime. `derives Optics`
unlocks `Optics[M](_.field)` so `update` / `updated` never write `copy` by hand.
""",
    section("The four pieces")(
      md"""
```scala
import conduit.*
import zio.*

case class CounterState(count: Int, history: List[Int]) derives Optics

enum CounterAction extends Action:
  case Inc, Dec, Reset
  case Set(v: Int)

val countHandler: ActionHandler[CounterState, Int, Nothing] =
  handle[CounterState, Int, Nothing](Optics[CounterState](_.count)):
    case CounterAction.Inc    => update(_ + 1)
    case CounterAction.Dec    => update(_ - 1)
    case CounterAction.Reset  => updated(0)
    case CounterAction.Set(v) => updated(v)
```
""",
      example {
        Mermoid.diagram(pieces)
      }.assert(ui => assertTrue(ui != null)),
    ),
    section("Dispatch is enqueue, then run")(
      md"""
`c(actions*)` offers onto the queue and returns. `c.run()` (default `terminate = true`) drains
until the queue is empty, including follow-ups. This example dispatches four actions and asserts
the final model.
""",
      exampleZIO {
        for
          c <- Conduit(CounterState(0, Nil))(countHandler)
          _ <- c(CounterAction.Inc, CounterAction.Inc, CounterAction.Set(10), CounterAction.Dec)
          _ <- c.run()
          s <- c.currentModel
        yield s
      }.assert(s => assertTrue(s.count == 9)),
    ),
    section("Live counter")(
      md"""
The widget below is a real `Conduit` with `run(false)` forked for the example scope, exposed to
the view as `Ctx` from **ascent-conduit** (docs-only). `ctx(Inc)` enqueues; the loop applies it;
`ctx.squawk(_.count)` patches the text node. Use `−` / `+` / Reset.
""",
      exampleIO {
        for
          (_, ctx) <- DocsRuntime.live(CounterState(0, Nil))(countHandler)
          count    <- ctx.squawk(_.count)
        yield E.div(
          E.button(Events.onClick(_ => ctx(CounterAction.Dec)), "−"),
          E.span(" ", count.map(_.toString), " "),
          E.button(Events.onClick(_ => ctx(CounterAction.Inc)), "+"),
          E.button(Events.onClick(_ => ctx(CounterAction.Reset)), "Reset"),
        )
      }.interactive,
    ),
    section("focus per case")(
      md"""
One `handle(Optics[M])` can retarget the ambient lens per branch with `focus(_.field)(...)`.
Same counter, handler focused on the whole model:
""",
      exampleZIO {
        for
          c <- Conduit(CounterState(0, Nil))(focusedHandler)
          _ <- c(CounterAction.Inc, CounterAction.Set(4))
          _ <- c.run()
          s <- c.currentModel
        yield s.count
      }.assert(n => assertTrue(n == 4)),
    ),
  )
end GettingStarted
