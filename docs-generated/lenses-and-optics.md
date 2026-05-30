# Lenses and Optics

A `Lens[M, V]` is a typed pair of functions: `get(m: M): V` and `set(m: M, v: V): M`. It lets you read and write a sub-part of an immutable model without manually threading `copy(...)` calls through nested case classes.

## Deriving lenses with `Optics`

Add `derives Optics` to a case class and you get a typeclass instance that lets the macro derive a `Lens` from a path:

```scala
import conduit.*

case class Address(city: String, zip: String) derives Optics
case class User(name: String, age: Int, address: Address) derives Optics
case class Model(user: User, count: Int) derives Optics
```

`Optics[M](path)` derives a `Lens[M, V]` by inspecting the path lambda at compile time. The path must be a chain of field accesses on the model — no method calls, no `if`/`match` expressions.

```scala
val nameLens   = Optics[Model](_.user.name)
val cityLens   = Optics[Model](_.user.address.city)
val countLens  = Optics[Model](_.count)

val initial = Model(User("Alice", 30, Address("NYC", "10001")), 0)

println(nameLens.get(initial))
println(cityLens.set(initial, "Boston"))
println(countLens.set(countLens.set(initial, 1), 2).count)
```
```
// Scala 3.8.3
Alice
Model(User(Alice,30,Address(Boston,10001)),0)
2
```

## What the macro generates

For `Optics[Model](_.user.address.city)`, the macro produces:

```
new Lens[Model, String]:
  def get(m: Model): String = m.user.address.city
  def set(m: Model, v: String): Model =
    m.copy(user = m.user.copy(address = m.user.address.copy(city = v)))
```

Plain `copy` chains. No reflection, no allocation beyond the new case class instances on the path. Reference-equal subtrees on the unmodified branches are preserved — important for [FastEq](fast-equality.md) optimization.

## Composing lenses

Lenses compose. Two ways:

**`>>` / `compose`** — chain a `Lens[A, B]` with a `Lens[B, C]` to get a `Lens[A, C]`:

```scala
val userLens    = Optics[Model](_.user)
val addressLens = Optics[User](_.address)
val cityLens    = Optics[Address](_.city)

// Compose three single-step lenses into a Lens[Model, String]
val composed: Lens[Model, String] = userLens >> addressLens >> cityLens

val m = Model(User("Alice", 30, Address("NYC", "10001")), 0)
println(composed.get(m))
println(composed.set(m, "LA").user.address.city)
```
```
// Scala 3.8.3
NYC
LA
```

**`apply` (the path macro)** — given a `Lens[M, V]`, derive a sub-lens via a path on `V`:

```scala
val userLens = Optics[Model](_.user)
val cityViaApply: Lens[Model, String] = userLens(_.address.city)

val m = Model(User("Alice", 30, Address("NYC", "10001")), 0)
println(cityViaApply.set(m, "SF").user.address.city)
```
```
// Scala 3.8.3
SF
```

`userLens(_.address.city)` and `Optics[Model](_.user.address.city)` produce equivalent lenses. The first is useful when you already have a base lens and want to extend it; the second when you're deriving a fresh path from the model root.

## What `Optics[M]` is by itself

`Optics[M]` (with no path) is the identity lens on the model — `get(m) = m`, `set(m, v) = v`. It's what you pass to the `handle` overload that operates on the whole model:

```scala
import zio.*

val identity: Lens[Model, Model] = Optics[Model]

val initial = Model(User("Alice", 30, Address("NYC", "10001")), 0)
println(identity.get(initial))
println(identity.set(initial, initial.copy(count = 99)).count)
```

You'll use this as the entry point for handlers that fan out across multiple sub-fields via `focus` — see [handlers.md](handlers.md#focus).

## Lens laws

Conduit's lenses obey the standard three laws:

- **get-set:** `lens.set(m, lens.get(m)) == m` — writing back what you read changes nothing.
- **set-get:** `lens.get(lens.set(m, v)) == v` — reading what you wrote yields the value.
- **set-set:** `lens.set(lens.set(m, a), b) == lens.set(m, b)` — the last write wins.

The macro-derived lenses for case-class fields satisfy all three. Some collection lenses ([collection-lenses.md](collection-lenses.md)) and `Iso`-derived lenses ([iso.md](iso.md)) have caveats — those docs spell them out.

## Errors at the path

The macro is strict. Non-field expressions are rejected at compile time:

```scala
// Method calls aren't valid lens paths
val bad = Optics[Model](_.user.name.length)
```
```
// Scala 3.8.3
// error: Only simple field accessors are supported, e.g., _.field got _$1.user.name.length()
```

```scala
// Pattern matches aren't valid either
val bad = Optics[Model](m => m.user match { case u => u.name })
```
```
// Scala 3.8.3
// error: Only simple field accessors are supported, e.g., _.field got m.user match {
  case u =>
    (u.name: scala.Predef.String)
}
```

That strictness is the macro's whole job — paths it accepts are guaranteed to be lawful lenses.

## Where to next

- **[handlers.md](handlers.md)** — using lenses to write action handlers.
- **[collection-lenses.md](collection-lenses.md)** — `at` / `key` for `List` / `Map` element access.
- **[iso.md](iso.md)** — re-typing a lens through a bidirectional transformation.
