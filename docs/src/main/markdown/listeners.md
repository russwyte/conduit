# Listeners

A `Listener[M, E, S]` watches a slice `S` of the model and runs an effect every time that slice changes. Use them for side effects that should fire in response to state — UI re-renders, log lines, network sends, persistence writes.

## Subscribing

`Conduit#subscribe` takes a path (or a `Lens`) and a callback `S => IO[E, Unit]`. The callback runs after every dispatch where the focused slice changed (per [FastEq](fast-equality.md)):

```scala marklit:silent,id=listener-base,show-warnings=false
import conduit.*
import zio.*

case class S(count: Int, label: String) derives Optics

enum Op extends Action:
  case Inc
  case Dec
  case SetLabel(s: String)

val handler: ActionHandler[S, S, Nothing] =
  handle[S, S, Nothing](Optics[S]):
    case Op.Inc           => focus(_.count)(update(_ + 1))
    case Op.Dec           => focus(_.count)(update(_ - 1))
    case Op.SetLabel(lbl) => focus(_.label)(updated(lbl))
```

```scala marklit:zio-app,extends=listener-base,show-warnings=false
for
  c    <- Conduit(S(0, "x"))(handler)
  seen <- Ref.make(List.empty[Int])
  _    <- c.subscribe(_.count)(n => seen.update(_ :+ n))
  _    <- c(Op.Inc, Op.Inc, Op.SetLabel("y"), Op.Inc, Op.Dec)
  _    <- c.run()
  ns   <- seen.get
  _    <- Console.printLine(s"count timeline: $ns")
yield ()
```

The `SetLabel` action didn't change `_.count`, so the listener didn't fire for it.

## Subscribing through `Subscribe` ops

Listeners can also be added via the `Subscribe` `ConduitOp`. This lets a *handler* return a follow-up that wires up a listener as part of a state transition:

```scala marklit:silent,extends=listener-base,id=op-listener-base,show-warnings=false
val followUp: ActionHandler[S, S, Nothing] =
  handle[S, S, Nothing](Optics[S]):
    case Op.Inc =>
      focus(_.count)(update(_ + 1))
    case Op.Dec =>
      focus(_.count)(update(_ - 1))
    case Op.SetLabel(lbl) =>
      // when label is set to "watch", subscribe a transient logger as a follow-up op
      m =>
        for
          listener <- Listener[S, Nothing, Int](Optics[S](_.count), n => Console.printLine(s"watcher: $n").orDie)
          set       = Optics[S](_.label).set(m, lbl)
        yield ActionResult(set, if lbl == "watch" then Subscribe(listener) else NoAction)
```

```scala marklit:zio-app,extends=op-listener-base,show-warnings=false
for
  c <- Conduit(S(0, "off"))(followUp)
  _ <- c(Op.Inc, Op.SetLabel("watch"), Op.Inc, Op.Inc)
  _ <- c.run()
yield ()
```

The first `Op.Inc` fires nothing (no listener yet). After `SetLabel("watch")`, the handler emits `Subscribe(listener)` as a follow-up; subsequent `Op.Inc`s notify it.

## Unsubscribing

Either via `Conduit#unsubscribe(listener)` directly:

```scala marklit:zio-app,extends=listener-base,show-warnings=false
for
  c        <- Conduit(S(0, "x"))(handler)
  count    <- Ref.make(0)
  listener <- c.subscribe(_.count)(_ => count.update(_ + 1))
  _        <- c(Op.Inc, Op.Inc)
  _        <- c.run()
  _        <- c.unsubscribe(listener)
  _        <- c(Op.Inc, Op.Inc)
  _        <- c.run()
  n        <- count.get
  _        <- Console.printLine(s"fired: $n")  // 2 — last two Incs ignored
yield ()
```

Or via the `Unsubscribe` op:

```scala marklit:zio-app,extends=listener-base,show-warnings=false
for
  c        <- Conduit(S(0, "x"))(handler)
  count    <- Ref.make(0)
  listener <- Listener[S, Nothing, Int](Optics[S](_.count), _ => count.update(_ + 1))
  _        <- c(Subscribe(listener), Op.Inc, Op.Inc, Unsubscribe(listener), Op.Inc)
  _        <- c.run()
  n        <- count.get
  _        <- Console.printLine(s"fired: $n")  // 2
yield ()
```

The op form is useful when you want subscribe/unsubscribe to be part of the action timeline rather than ambient setup.

## What happens when a listener fails

A listener whose effect fails aborts the dispatch loop. Conduit is fail-fast — it doesn't try to keep going after a listener crashes:

```scala marklit:zio-app,extends=listener-base,show-warnings=false
sealed trait Err
case object Boom extends Err

for
  c    <- Conduit(S(0, "x"))(handler.widen[Err])
  _    <- c.subscribe(Optics[S](_.count))(n => if n >= 2 then ZIO.fail(Boom) else ZIO.unit)
  _    <- c(Op.Inc, Op.Inc, Op.Inc)  // second Inc trips the listener
  exit <- c.run().either
  _    <- Console.printLine(s"either: $exit")
yield ()
```

Recoverable failures should be handled *inside* the listener's effect (`.catchAll`, `.either`, etc.). Conduit surfaces only what the listener lets escape.

## The `unsafe` API

For interop with code that can't `await` a ZIO effect (UI threads, JS event loops), every `Conduit` exposes a synchronous façade at `conduit.unsafe`:

```scala marklit:silent,extends=listener-base,id=unsafe,show-warnings=false
val c = Conduit.make(S(0, "x"))(handler)

// blocking dispatch + run
c.unsafe(Op.Inc, Op.Inc)
c.unsafe.run()

// blocking subscribe
val listener = c.unsafe.subscribe(_.count)(n => println(s"saw $n"))

c.unsafe(Op.Inc)
c.unsafe.run()

c.unsafe.unsubscribe(listener)
```

```scala marklit:zio-app,extends=unsafe,show-warnings=false
ZIO.unit
```

All `unsafe.*` calls execute on `Runtime.default`. Same semantics, blocking signature.

## Where to next

- **[fast-equality.md](fast-equality.md)** — when listeners *don't* fire, and how to control that.
- **[handlers.md](handlers.md)** — emitting `Subscribe` / `Unsubscribe` from handlers.
