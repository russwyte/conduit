# Handlers

An `ActionHandler[M, V, E]` is a partial function from action → state transition, scoped to a sub-slice `V` of the model `M`, with typed effect errors `E`. This doc covers building, composing, and parameterizing them.

## The core helpers

Inside `handle(lens) { partial-function }`, the lens becomes ambient and three helpers operate against it:

- `update(f)` — read the slice, transform it, write it back.
- `updated(v)` — set the slice to a literal value.
- `noChange` — leave the model alone (still produces a clean `ActionResult`).

```scala
import conduit.*
import zio.*

case class State(count: Int, label: String) derives Optics

enum Op extends Action:
  case Inc
  case Dec
  case ResetCount
  case SetLabel(s: String)
  case TouchOnly
```

```scala
val countLens = Optics[State](_.count)

val countHandler: ActionHandler[State, Int, Nothing] =
  handle[State, Int, Nothing](countLens):
    case Op.Inc        => update(_ + 1)
    case Op.Dec        => update(_ - 1)
    case Op.ResetCount => updated(0)
    case Op.TouchOnly  => noChange
```

```scala
for
  c <- Conduit(State(0, "x"))(countHandler)
  _ <- c(Op.Inc, Op.Inc, Op.Inc, Op.Dec, Op.TouchOnly)
  _ <- c.run()
  s <- c.currentModel
  _ <- Console.printLine(s)
yield ()
```
```
// Scala 3.8.3
State(2,x)
```

## `focus`: changing slice within one handler

A `handle(lens) { ... }` block fixes the slice. To act on a different sub-field per case without splitting into multiple handlers, use `focus`:

```scala
val handler: ActionHandler[State, State, Nothing] =
  handle[State, State, Nothing](Optics[State]):
    case Op.Inc           => focus(_.count)(update(_ + 1))
    case Op.Dec           => focus(_.count)(update(_ - 1))
    case Op.ResetCount    => focus(_.count)(updated(0))
    case Op.SetLabel(s)   => focus(_.label)(updated(s))
    case Op.TouchOnly     => focus(_.count)(noChange)
```

```scala
for
  c <- Conduit(State(0, "x"))(handler)
  _ <- c(Op.Inc, Op.SetLabel("y"), Op.Inc, Op.SetLabel("z"))
  _ <- c.run()
  s <- c.currentModel
  _ <- Console.printLine(s)
yield ()
```
```
// Scala 3.8.3
State(2,z)
```

`focus(path)(body)` re-binds the ambient `Lens[M, V]` to a sub-focus `Lens[M, W]` for `body`. Inside the body, `update` / `updated` use the sub-lens automatically. Paths nest:

```scala
import conduit.*
import zio.*

case class Address(city: String) derives Optics
case class User(name: String, address: Address) derives Optics
case class App(user: User) derives Optics

enum AppOp extends Action:
  case MoveTo(c: String)
  case MoveDeep(c: String)

val nested: ActionHandler[App, App, Nothing] =
  handle[App, App, Nothing](Optics[App]):
    // direct deep path
    case AppOp.MoveTo(c)   => focus(_.user.address.city)(updated(c))
    // nested focus — same end result
    case AppOp.MoveDeep(c) => focus(_.user)(focus(_.address.city)(updated(c)))
```

```scala
for
  c <- Conduit(App(User("Alice", Address("NYC"))))(nested)
  _ <- c(AppOp.MoveTo("LA"))
  s <- c.currentModel
  _ <- Console.printLine(s)
yield ()
```
```
// Scala 3.8.3
App(User(Alice,Address(NYC)))
```

## Composing handlers: `>>` vs `++`

Two ways to combine handlers that operate on the same model.

**`>>` (orElse)** — try the first, fall through to the second if it didn't match. Each action runs at most one handler:

```scala
import conduit.*
import zio.*

case class M(a: Int, b: Int) derives Optics

enum Op extends Action:
  case IncA
  case IncB
  case IncBoth

val ha: ActionHandler[M, Int, Nothing] =
  handle[M, Int, Nothing](Optics[M](_.a)):
    case Op.IncA => update(_ + 1)
    case Op.IncBoth => update(_ + 1)

val hb: ActionHandler[M, Int, Nothing] =
  handle[M, Int, Nothing](Optics[M](_.b)):
    case Op.IncB => update(_ + 1)
    case Op.IncBoth => update(_ + 1)
```

```scala
val orElse = ha >> hb
for
  c <- Conduit(M(0, 0))(orElse)
  _ <- c(Op.IncA, Op.IncB, Op.IncBoth)
  _ <- c.run()
  m <- c.currentModel
  _ <- Console.printLine(s"orElse: $m")  // a=2 (IncA + IncBoth), b=1 (IncB only — IncBoth never reached hb)
yield ()
```
```
// Scala 3.8.3
orElse: M(2,1)
```

**`++` (fold)** — run *both* handlers when both match, threading the model through. The first runs against the original model, the second against the result:

```scala
val fold = ha ++ hb
for
  c <- Conduit(M(0, 0))(fold)
  _ <- c(Op.IncA, Op.IncB, Op.IncBoth)
  _ <- c.run()
  m <- c.currentModel
  _ <- Console.printLine(s"fold: $m")  // a=2 (IncA + IncBoth), b=2 (IncB + IncBoth)
yield ()
```
```
// Scala 3.8.3
fold: M(2,2)
```

Use `>>` when an action belongs to one slice and you want the first matching handler to win. Use `++` when several handlers genuinely need to react to the same action.

## Typed errors

A handler's error type `E` threads through `IO[E, ActionResult[M, E]]`. The handler can `ZIO.fail(typedError)` to abort dispatch.

```scala
import conduit.*
import zio.*

case class S(value: Int) derives Optics

enum Op extends Action:
  case Set(v: Int)
  case AssertEven

// One error hierarchy: MyErr is a subtype of Err so we can widen later.
sealed trait Err
sealed trait MyErr extends Err
case object NotEven extends MyErr
case object Unknown extends Err

val h: ActionHandler[S, Int, MyErr] =
  handle[S, Int, MyErr](Optics[S](_.value)):
    case Op.Set(v)     => updated(v)
    case Op.AssertEven =>
      // The model `m` is the whole S; check the focused field via the lens.
      m => if Optics[S](_.value).get(m) % 2 == 0 then noChange[S, MyErr].apply(m) else ZIO.fail(NotEven)
```

```scala
for
  c    <- Conduit(S(5))(h)
  _    <- c(Op.AssertEven)
  exit <- c.run().exit
  _    <- Console.printLine(s"exit: $exit")
yield ()
```
```
// Scala 3.8.3
exit: Failure(Fail(NotEven,Stack trace for thread "zio-fiber-834944977":
	at <empty>.MarklitWrapper.run.effect.h(marklit_16330990960985753308.scala:26)
	at conduit.Conduit.dispatch(Conduit.scala:88)
	at conduit.Conduit.run.loop(Conduit.scala:47)
	at <empty>.MarklitWrapper.run.effect(marklit_16330990960985753308.scala:32)
	at <empty>.MarklitWrapper.run.effect(marklit_16330990960985753308.scala:34)
	at <empty>.MarklitWrapper.run(marklit_16330990960985753308.scala:42)))
```

### Unhandled actions

By default, an action no handler matches becomes a `Throwable` defect — appropriate when `E = Nothing`. Provide a typed mapping with `onUnhandled`:

```scala
val safer = h.widen[Err].onUnhandled(_ => Unknown)

enum Other extends Action:
  case Inc
```

```scala
// AssertEven on an even number → handled, no error
// Other.Inc → no case matches → Unknown
for
  c    <- Conduit(S(4))(safer)
  _    <- c(Other.Inc)
  exit <- c.run().either
  _    <- Console.printLine(s"either: $exit")
yield ()
```
```
// Scala 3.8.3
either: Left(Unknown)
```

`widen[E2 >: E]` widens the error channel to a supertype — handy when composing a `Nothing`-error handler with one that can fail.

## Follow-up actions

A handler can enqueue more actions as part of its `ActionResult`. The dispatch loop processes them before exiting:

```scala
import conduit.*
import zio.*

case class S(value: Int) derives Optics

enum Op extends Action:
  case Inc, IncTwice
```

```scala
val h: ActionHandler[S, Int, Nothing] =
  handle[S, Int, Nothing](Optics[S](_.value)):
    case Op.Inc =>
      update(_ + 1)
    case Op.IncTwice =>
      // increment once, queue another Inc as a follow-up
      val lens = Optics[S](_.value)
      m => ZIO.succeed(ActionResult(lens.set(m, m.value + 1), Op.Inc))
```

```scala
for
  c <- Conduit(S(0))(h)
  _ <- c(Op.IncTwice)
  _ <- c.run()
  s <- c.currentModel
  _ <- Console.printLine(s)  // S(2)
yield ()
```
```
// Scala 3.8.3
S(2)
```

`run(terminate = true)` (the default) drains follow-ups too — it only exits when the queue is empty. Use `run(terminate = false)` for a long-running app that should keep listening for new actions until you dispatch `Done`.

## `effectOnly`: side effects without state changes

`effectOnly(f: M => IO[E, Unit])` runs an effect against the whole model and returns a clean `ActionResult` (model unchanged):

```scala
val logHandler: ActionHandler[S, S, Nothing] =
  handle[S, S, Nothing](Optics[S]):
    case Op.Inc =>
      effectOnly(s => Console.printLine(s"saw $s").orDie)
```

```scala
for
  c <- Conduit(S(7))(logHandler)
  _ <- c(Op.Inc)
  _ <- c.run()
yield ()
```
```
// Scala 3.8.3
saw S(7)
```

`effectOnly` always sees the whole model, even inside a `focus(path)(...)` body — `focus` rebinds `update` / `updated`, but `effectOnly` doesn't take a slice.

## Where to next

- **[listeners.md](listeners.md)** — react to model changes outside the dispatch loop.
- **[fast-equality.md](fast-equality.md)** — skip listener notifications when nothing changed.
- **[collection-lenses.md](collection-lenses.md)** — `focus(_.items)` paired with element-access primitives.
