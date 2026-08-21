package conduit.docs

import _root_.conduit.*
import ascent.*
import ascent.dsl.*
import specular.*
import specular.mermoid.Mermoid
import zio.*
import zio.test.*

object Handlers extends DocSpec:

  case class State(count: Int, label: String) derives Optics

  enum Op extends Action:
    case Inc
    case Dec
    case ResetCount
    case SetLabel(s: String)
    case TouchOnly
    case IncTwice

  private val countHandler: ActionHandler[State, Int, Nothing] =
    handle[State, Int, Nothing](Optics[State](_.count)):
      case Op.Inc        => update(_ + 1)
      case Op.Dec        => update(_ - 1)
      case Op.ResetCount => updated(0)
      case Op.TouchOnly  => noChange

  private val focused: ActionHandler[State, State, Nothing] =
    handle[State, State, Nothing](Optics[State]):
      case Op.Inc         => focus(_.count)(update(_ + 1))
      case Op.Dec         => focus(_.count)(update(_ - 1))
      case Op.ResetCount  => focus(_.count)(updated(0))
      case Op.SetLabel(s) => focus(_.label)(updated(s))
      case Op.TouchOnly   => focus(_.count)(noChange)
      case Op.IncTwice =>
        val lens = Optics[State](_.count)
        m => ZIO.succeed(ActionResult(lens.set(m, m.count + 1), Op.Inc))

  private val composeDiag =
    """flowchart TB
      |  Act[Action] --> OrElse["h1 >> h2 first match"]
      |  Act --> Fold["h1 ++ h2 both match"]
      |  Fold --> D[dirty OR]
      |  Fold --> N[next concatenated]
      |""".stripMargin

  def doc = page("Handlers")(
    md"""
An `ActionHandler[M, V, E]` is a partial function from action to `IO[E, ActionResult[M, E]]`,
scoped to slice `V`. Inside `handle(lens) { ... }` the lens is ambient: `update`, `updated`,
and `noChange` read and write that slice.
""",
    section("update, updated, noChange")(
      md"""
`noChange` produces a **clean** `ActionResult`. Dispatch-level FastEq then skips every listener.
""",
      exampleZIO {
        for
          c <- Conduit(State(0, "x"))(countHandler)
          _ <- c(Op.Inc, Op.Inc, Op.Dec, Op.TouchOnly)
          _ <- c.run()
          s <- c.currentModel
        yield s.count
      }.assert(n => assertTrue(n == 1)),
    ),
    section("Live: focus and follow-ups")(
      md"""
`Inc` / `Dec` / `Reset` move `count`. `Set label` writes `label` through `focus`. **Inc twice**
increments once in the handler and returns `Inc` as `ActionResult.next`, so one click becomes
two dispatches.
""",
      example {
        Mermoid.diagram(composeDiag)
      }.assert(ui => assertTrue(ui != null)),
      exampleIO {
        for
          (_, ctx) <- DocsRuntime.live(State(0, "x"))(focused)
          count    <- ctx.squawk(_.count)
          label    <- ctx.squawk(_.label)
        yield E.div(
          E.p("count: ", E.strong(count.map(_.toString)), "  label: ", E.strong(label)),
          E.button(Events.onClick(_ => ctx(Op.Dec)), "−"),
          E.button(Events.onClick(_ => ctx(Op.Inc)), "+"),
          E.button(Events.onClick(_ => ctx(Op.IncTwice)), "Inc twice"),
          E.button(Events.onClick(_ => ctx(Op.ResetCount)), "Reset"),
          E.button(Events.onClick(_ => ctx(Op.SetLabel("on"))), "Label on"),
          E.button(Events.onClick(_ => ctx(Op.SetLabel("off"))), "Label off"),
        )
      }.interactive,
      exampleZIO {
        for
          c <- Conduit(State(0, "x"))(focused)
          _ <- c(Op.IncTwice)
          _ <- c.run()
          s <- c.currentModel
        yield s.count
      }.assert(n => assertTrue(n == 2)),
    ),
    section(">> vs ++")(
      md"""
`>>` (`orElse`) is first-match composition: split handlers by slice, try the left one, then
the right. `++` (`fold`) runs **both** when they match the same action. The second sees the
first's model; `next` concatenates; `dirty` is OR so `update ++ noChange` still notifies.
""",
      exampleIO {
        Mermoid.diagramInteractive(composeDiag, initialWidth = 560)
      }.interactive,
    ),
    section("Unhandled and errors")(
      md"""
The default unhandled path is a defect. `onUnhandled` maps the action to a typed `E`.
`widen[E2]` is for composing a `Nothing` handler with one that can fail. `effectOnly` runs a
side effect and returns a clean result; it always sees the **whole** model, even inside `focus`.
"""
    ),
  )
end Handlers
