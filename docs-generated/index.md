# Conduit

A small Scala 3 / ZIO 2 unidirectional state-management library. Think Redux, but with first-class lenses and typed effects.

> **Note:** Code in these docs runs on the JVM via [marklit](https://github.com/russwyte/marklit). The library itself cross-builds for **JVM, Scala.js, and Scala Native** — only the doc generator is JVM-only.

## Read these in order

1. **[getting-started.md](getting-started.md)** — Model → Action → Handler → Conduit, end-to-end in one file.
2. **[lenses-and-optics.md](lenses-and-optics.md)** — `Optics`, `Lens`, composition. The macro that derives lenses for case-class fields.
3. **[handlers.md](handlers.md)** — Building action handlers: `update` / `updated`, the `focus` combinator, composing with `>>` and `++`, error handling, follow-up actions.
4. **[listeners.md](listeners.md)** — Subscribing to changes via `subscribe` / `Subscribe`, `Listener`.
5. **[fast-equality.md](fast-equality.md)** — `FastEq` strategies for skipping no-op notifications.

## Topic guides

- **[collection-lenses.md](collection-lenses.md)** — Lens primitives for `List`, `Vector`, `Map` element access.
- **[iso.md](iso.md)** — `Iso`, `imap`, `xmap` — re-typing a lens through a bidirectional transformation.

## At a glance

```scala
import conduit.*
import zio.*

case class State(count: Int) derives Optics

enum Counter extends Action:
  case Inc, Dec

val handler: ActionHandler[State, Int, Nothing] =
  handle[State, Int, Nothing](Optics[State](_.count)):
    case Counter.Inc => update(_ + 1)
    case Counter.Dec => update(_ - 1)

for
  c     <- Conduit(State(0))(handler)
  _     <- c(Counter.Inc, Counter.Inc, Counter.Inc, Counter.Dec)
  _     <- c.run()
  state <- c.currentModel
  _     <- Console.printLine(s"final: $state")
yield ()
```
```
// Scala 3.8.3
final: State(2)
```

That's the whole shape. Subsequent docs add layers: how to compose handlers when state has multiple slices, how to subscribe to a focused part of the model, how to skip listener work when the slice didn't change.
