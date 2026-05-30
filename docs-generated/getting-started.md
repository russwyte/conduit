# Getting Started

A complete Conduit application — model, action, handler, runtime — in under twenty lines.

## The four pieces

A Conduit app has four moving parts:

1. **Model** — an immutable case class describing the application state.
2. **Actions** — values describing *what should happen*. Pattern-matched, never executed.
3. **Handler** — a partial function from action → state transition.
4. **Conduit** — the runtime: an action queue, a `Ref` holding the model, a dispatch loop.

Define a model. The `derives Optics` clause is what unlocks the lens DSL — see [lenses-and-optics.md](lenses-and-optics.md) for what it does.

```scala
import conduit.*
import zio.*

case class CounterState(count: Int, history: List[Int]) derives Optics

enum CounterAction extends Action:
  case Inc
  case Dec
  case Reset
  case Set(v: Int)
```

Now the handler. `handle(lens) { partial-function }` makes `update` / `updated` operate against the lensed slice — there's no `model.copy(count = ...)` boilerplate.

```scala
val countLens = Optics[CounterState](_.count)

val countHandler: ActionHandler[CounterState, Int, Nothing] =
  handle[CounterState, Int, Nothing](countLens):
    case CounterAction.Inc    => update(_ + 1)
    case CounterAction.Dec    => update(_ - 1)
    case CounterAction.Reset  => updated(0)
    case CounterAction.Set(v) => updated(v)
```

Wire it up and dispatch:

```scala
for
  c <- Conduit(CounterState(0, Nil))(countHandler)
  _ <- c(CounterAction.Inc, CounterAction.Inc, CounterAction.Set(10), CounterAction.Dec)
  _ <- c.run()
  s <- c.currentModel
  _ <- Console.printLine(s"final: $s")
yield ()
```
```
// Scala 3.8.3
final: CounterState(9,List())
```

## What just happened

- `c(actions*)` enqueues `Dispatchable` values onto the conduit's queue. It's non-blocking.
- `c.run()` drains the queue. By default it terminates when the queue is empty, including any follow-up actions a handler returns. Pass `false` to run-until-`Done` instead.
- The handler runs once per action, atomically updates the model in a `Ref`, and (if the slice changed) notifies any subscribed listeners.

## Updating multiple fields with one action

Right now the handler is focused on a single `Int` slice. To touch a different field per action — for example, recording history when the count changes — we have two patterns:

**Option 1: focus per case.** Use the `focus` combinator to re-bind the ambient lens for each branch:

```scala
val focusedHandler: ActionHandler[CounterState, CounterState, Nothing] =
  handle[CounterState, CounterState, Nothing](Optics[CounterState]):
    case CounterAction.Inc    => focus(_.count)(update(_ + 1))
    case CounterAction.Dec    => focus(_.count)(update(_ - 1))
    case CounterAction.Reset  => focus(_.count)(updated(0))
    case CounterAction.Set(v) => focus(_.count)(updated(v))
```

**Option 2: compose smaller handlers.** Each handler focuses on its own slice; combine with `>>` (orElse) or `++` (fold). See [handlers.md](handlers.md) for the difference.

```scala
for
  c <- Conduit(CounterState(0, Nil))(focusedHandler)
  _ <- c(CounterAction.Inc, CounterAction.Inc, CounterAction.Set(10))
  _ <- c.run()
  s <- c.currentModel
  _ <- Console.printLine(s"final: $s")
yield ()
```
```
// Scala 3.8.3
final: CounterState(10,List())
```

## Where to next

- **[lenses-and-optics.md](lenses-and-optics.md)** — how `Optics` and `Lens` work; what the macro generates.
- **[handlers.md](handlers.md)** — `>>` vs `++`, `onUnhandled`, `widen`, follow-up actions.
- **[listeners.md](listeners.md)** — subscribing to a focused slice of the model.
