package conduit.docs

import _root_.conduit.*
import ascent.*
import ascent.dsl.*
import specular.*
import specular.mermoid.Mermoid
import zio.*
import zio.test.*

object Listeners extends DocSpec:

  case class S(count: Int, label: String) derives Optics

  enum Op extends Action:
    case Inc
    case Dec
    case SetLabel(s: String)

  private val handler: ActionHandler[S, S, Nothing] =
    handle[S, S, Nothing](Optics[S]):
      case Op.Inc           => focus(_.count)(update(_ + 1))
      case Op.Dec           => focus(_.count)(update(_ - 1))
      case Op.SetLabel(lbl) => focus(_.label)(updated(lbl))

  private val slice =
    """flowchart LR
      |  M[Model] --> C[count listener]
      |  M --> L[label listener]
      |  Inc[Inc] --> M
      |  Lab[SetLabel] --> M
      |  Inc -.-> C
      |  Lab -.-> L
      |""".stripMargin

  def doc = page("Listeners")(
    md"""
A `Listener[M, E, S]` watches slice `S` and runs `S => IO[E, Unit]` when that slice changes
per [FastEq](fast-equality.html). `c.subscribe(_.count)(cb)` registers immediately (it does
not wait for `run()`). The `Subscribe` / `Unsubscribe` ops exist so a **handler** can wire a
listener as a follow-up.
""",
    section("Slice notifications")(
      md"""
Changing `label` does not fire a `_.count` listener. The timeline below only records count.
""",
      example {
        Mermoid.diagram(slice)
      }.assert(ui => assertTrue(ui != null)),
      exampleZIO {
        for
          c    <- Conduit(S(0, "x"))(handler)
          seen <- Ref.make(List.empty[Int])
          _    <- c.subscribe(_.count)(n => seen.update(_ :+ n))
          _    <- c(Op.Inc, Op.Inc, Op.SetLabel("y"), Op.Inc, Op.Dec)
          _    <- c.run()
          ns   <- seen.get
        yield ns
      }.assert(ns => assertTrue(ns == List(1, 2, 3, 2))),
    ),
    section("Live log")(
      md"""
The log appends only when **count** changes. Toggle the label: the log stays still. `+/−`
append a line.
""",
      exampleIO {
        for
          (c, ctx) <- DocsRuntime.live(S(0, "x"))(handler)
          log      <- sq(List.empty[String])
          _        <- c.subscribe(_.count)(n => log.update(_ :+ s"count=$n").unit)
          count    <- ctx.squawk(_.count)
          label    <- ctx.squawk(_.label)
        yield E.div(
          E.p("count ", E.strong(count.map(_.toString)), "  label ", E.strong(label)),
          E.button(Events.onClick(_ => ctx(Op.Dec)), "−"),
          E.button(Events.onClick(_ => ctx(Op.Inc)), "+"),
          E.button(Events.onClick(_ => ctx(Op.SetLabel("alpha"))), "Label alpha"),
          E.button(Events.onClick(_ => ctx(Op.SetLabel("beta"))), "Label beta"),
          E.pre(log.map(lines => if lines.isEmpty then "(no count events yet)" else lines.mkString("\n"))),
        )
      }.interactive,
    ),
    section("Subscribe as a follow-up")(
      md"""
A handler can return `Subscribe(listener)` in `ActionResult.next`. Until that op runs, the
callback never fires. `c.unsubscribe(listener)` removes it; there is also an `Unsubscribe` op.
""",
      exampleIO {
        Mermoid.diagramInteractive(slice, initialWidth = 640)
      }.interactive,
    ),
  )
end Listeners
