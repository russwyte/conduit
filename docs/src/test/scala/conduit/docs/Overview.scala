package conduit.docs

import specular.*
import specular.mermoid.Mermoid
import zio.test.*

object Overview extends DocSpec:

  private val loop =
    """flowchart LR
      |  Act[Action] --> Q[Queue]
      |  Q --> Disp[Dispatch]
      |  Disp --> H[Handler]
      |  H --> R[Ref]
      |  R --> Eq{FastEq}
      |  Eq -->|changed| L[Listeners]
      |  Eq -->|same| Skip[Skip notify]
      |  H --> Next[Follow-ups]
      |  Next --> Disp
      |""".stripMargin

  def doc = page("Overview")(
    md"""
**Conduit** is a Scala 3 / ZIO 2 library for unidirectional state. Actions describe *what happened*.
Handlers turn those into a new immutable model. Listeners react to slices that actually changed.

It cross-builds for **JVM, Scala.js, and Scala Native**. The docs site is JVM + a Scala.js client
that remounts the live widgets; the library itself has no UI dependency.

```scala
libraryDependencies += "rocks.earlyeffect" %% "conduit" % "<version>"
```

Use `%%%` in a Scala.js or Native build. Pair with
[ascent-conduit](https://www.earlyeffect.rocks/ascent/) when the host is an ascent UI; that bridge
is optional and lives in ascent, not here.
""",
    section("The loop")(
      md"""
Enqueue is cheap and asynchronous. **Nothing is applied until `run()`**. Follow-ups returned from a
handler are dispatched immediately (nested), then the loop continues. Click a node in the diagram
to highlight it.
""",
      example {
        Mermoid.diagram(loop)
      }.assert(ui => assertTrue(ui != null)),
      exampleIO {
        Mermoid.diagramInteractive(loop, initialWidth = 720)
      }.interactive,
    ),
    section("Four pieces")(
      md"""
| Piece | Job |
| --- | --- |
| **Model** | Immutable case class, usually `derives Optics` |
| **Action** | `enum X extends Action` (alias for `AppAction[Any, Nothing]`) |
| **Handler** | Partial function from action to `ActionResult` on a lensed slice |
| **Conduit** | Queue + `Ref` + dispatch loop + listeners |

Read [Getting started](getting-started.html) for a clickable counter, then [Mental model](mental-model.html)
for how FastEq, follow-ups, and `run` vs `run(false)` fit together.
"""
    ),
  )
end Overview
