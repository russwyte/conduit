# FastEq — Skipping no-op notifications

`FastEq[A]` is an *optional* equality typeclass that Conduit uses in two places:

1. **Per listener.** Before calling a listener, Conduit re-extracts its slice and compares it to what the listener last saw. Equal? Skip the call.
2. **At dispatch.** After every action, Conduit compares the new model to the old. Equal? Skip *all* listener evaluation.

When you don't supply a `given FastEq[M]`, Conduit falls back to `==` (via `FastEq.fromEquals`). That's the safe default. `FastEq` lets you opt into a *faster* check at the cost of a tiny instance.

## When `==` is fine

For small models, `==` is fast and correct. Don't reach for `FastEq` until you've measured: a deeply-nested model, expensive `equals`, or a hot dispatch loop.

```scala
import conduit.*
import zio.*

case class Counter(value: Int) derives Optics
// no `given FastEq[Counter]` — falls back to `==`

enum Op extends Action:
  case Inc
  case NoOp

val handler: ActionHandler[Counter, Int, Nothing] =
  handle[Counter, Int, Nothing](Optics[Counter](_.value)):
    case Op.Inc  => update(_ + 1)
    case Op.NoOp => noChange  // produces a clean ActionResult — model unchanged
```

```scala
for
  c     <- Conduit(Counter(0))(handler)
  count <- Ref.make(0)
  _     <- c.subscribe(_.value)(_ => count.update(_ + 1))
  _     <- c(Op.NoOp, Op.NoOp, Op.NoOp, Op.Inc, Op.NoOp)
  _     <- c.run()
  fired <- count.get
  _     <- Console.printLine(s"listener fired: $fired times (only the Inc moved the model)")
yield ()
```
```
// Scala 3.8.3
listener fired: 1 times (only the Inc moved the model)
```

The `NoOp` actions never reached the listener: `noChange` returns a clean `ActionResult`, which short-circuits dispatch — no equality check even runs.

## Built-in factories

These are the actual factories on `FastEq`. Pick one, define a `given`, and Conduit uses it:

| Factory                                | Use when                                                                          |
| -------------------------------------- | --------------------------------------------------------------------------------- |
| `FastEq.fromEquals[A]`                 | You want explicit `==`. (Same as no instance — fallback default.)                 |
| `FastEq.instance((a,b) => …)`          | You want full control over the comparison.                                        |
| `FastEq.withReferenceEquality(fb)`     | Try `eq` first; fall through to `fb` on miss. Cheap when handlers preserve subtrees. |
| `FastEq.fromVersion(_.version)`        | Your model has a monotonically-bumped version field.                              |
| `FastEq.fromHash(_.cachedHash)`        | Your model carries a precomputed hash.                                            |
| `FastEq.withDirtyFlag(_.isDirty, fb)`  | Your model has an explicit dirty flag; clean-clean short-circuits to equal.       |
| `FastEq.derived`                       | Used with `derives FastEq` — equivalent to `fromEquals`.                          |

## `withReferenceEquality` — the cheapest case

Lenses preserve reference equality on unchanged sibling subtrees. So if a handler runs `Optics[M](_.a).set(m, ...)`, the resulting model has a different `eq` identity, but its `_.b`, `_.c`, etc. are the *same* references they were on the input. A listener watching `_.b` can compare with `eq` first and only fall back to structural equality when references differ:

```scala
import conduit.*

case class Sub(payload: List[String])
case class Big(a: Int, sub: Sub) derives Optics

// `eq` first; fall back to `==` if not eq.
given FastEq[Sub] = FastEq.withReferenceEquality(FastEq.fromEquals[Sub])
```

```scala
val sub = Sub(List("x", "y", "z"))
val m1  = Big(0, sub)
val m2  = Optics[Big](_.a).set(m1, 1)  // changes only `_.a`

val eqv = FastEq[Sub]
println(s"sub eq?       ${m2.sub eq m1.sub}")           // true — lens preserved it
println(s"FastEq[Sub]?  ${eqv.eqv(m2.sub, m1.sub)}")    // true via the eq path, no list scan
```
```
// Scala 3.8.3
sub eq?       true
FastEq[Sub]?  true
```

Note: this only short-circuits when handlers *don't* re-allocate the watched subtree. `Optics[Big](_.sub).set(m, m.sub)` still allocates a new `Big` whose `_.sub` field points to the same `Sub` instance — so `eq` still wins. But `Optics[Big](_.sub).set(m, Sub(m.sub.payload))` produces a `Big` whose `_.sub` is a freshly-allocated `Sub` — `eq` fails, and the fallback runs.

## `fromVersion` — the cheapest fast-path

When a model carries an explicit version that you bump on every meaningful change:

```scala
import conduit.*

case class Doc(version: Long, body: String)

// Two Docs are equal iff their versions match. The body isn't compared.
given FastEq[Doc] = FastEq.fromVersion(_.version)
```

```scala
val a = Doc(1, "hello")
val b = Doc(1, "different body, same version")  // intentional: tests the contract
val c = Doc(2, "hello")

println(FastEq[Doc].eqv(a, b))  // true  — same version
println(FastEq[Doc].eqv(a, c))  // false — different version
```
```
// Scala 3.8.3
true
false
```

This is the right shape when you control writes (e.g. via handlers): bump the version inside the handler whenever you make a meaningful change, and FastEq becomes a single `Long` comparison regardless of model size. It's the user's responsibility to keep the version in sync with the data — same trade-off as a CRDT.

## `withDirtyFlag` — short-circuit clean-clean

When your model has an `isDirty` flag (e.g. set by an editor when the document has unsaved changes):

```scala
import conduit.*

case class Doc(body: String, isDirty: Boolean)

given FastEq[Doc] = FastEq.withDirtyFlag(_.isDirty, FastEq.fromEquals[Doc])
```

```scala
val clean1 = Doc("hello", false)
val clean2 = Doc("world", false)  // different body — but both clean!
val dirty1 = Doc("hello", true)
val dirty2 = Doc("hello", true)

println(FastEq[Doc].eqv(clean1, clean2))  // true  — both clean, never compared bodies
println(FastEq[Doc].eqv(clean1, dirty1))  // false — flags differ
println(FastEq[Doc].eqv(dirty1, dirty2))  // true  — both dirty → falls back to `==`
```
```
// Scala 3.8.3
true
false
true
```

The "both clean → equal" rule is *load-bearing*: it lets a listener-driven UI skip re-renders when nothing in the editor moved. If you actually need clean docs to compare structurally (e.g. for "are these two saved files the same"?), pick a different strategy.

## Resolving the instance

`FastEq.get[A]` is the runtime way to ask "what's the resolved instance for `A`?". It returns the user-supplied `given` if one exists, or the `==`-based fallback otherwise. Conduit calls this once per Conduit instance and caches the result.

```scala
import conduit.*

case class A(n: Int)
// no given FastEq[A]
val eqA = FastEq.get[A]

println(eqA.eqv(A(1), A(1)))  // true — falls back to ==
println(eqA.eqv(A(1), A(2)))  // false
```

## Built-in instances

`FastEq` ships defaults for `String`, `Int`, `Long`, `Double`, `Boolean`, plus derived instances for `Option[A]`, `List[A]`, `Vector[A]`, and `Map[K, V]` whenever the element type has a `FastEq`. The collection instances do a length check first, then element-wise comparison via the element `FastEq`.

```scala
import conduit.*

val intsEq = FastEq.get[List[Int]]

println(intsEq.eqv(List(1, 2, 3), List(1, 2, 3)))     // true
println(intsEq.eqv(List(1, 2, 3), List(1, 2)))        // false (length check)
println(intsEq.eqv(List(1, 2, 3), List(1, 2, 4)))     // false (element check)
```

## Where to next

- **[handlers.md](handlers.md)** — emitting `noChange` to skip the equality check entirely.
- **[listeners.md](listeners.md)** — what happens when listeners do fire.
