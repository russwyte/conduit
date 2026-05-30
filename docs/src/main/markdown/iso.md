# Iso, imap, xmap

An `Iso[A, B]` is a faithful bidirectional transformation: `to: A => B` and `from: B => A` such that `from(to(a)) == a` and `to(from(b)) == b`. Conduit uses it for re-typing the *focus* of a lens — viewing the same underlying field through a different type.

The contract is on the caller: marklit can't check that your `to` and `from` are inverses. When they are, lens laws on the source lens carry over to the derived lens. When they aren't, you'll see law violations exactly where the iso loses information.

## Importing

```scala marklit:silent,id=iso-base
import conduit.*
import conduit.Iso.*
```

`Iso` itself is just a case class. `imap` / `xmap` are extension methods on `Lens` that live in the `Iso` companion, so the import above brings them in.

## `imap`: re-type via an `Iso`

Given `Lens[M, V]` and `Iso[V, W]`, get `Lens[M, W]`:

```scala marklit:silent,extends=iso-base,id=int-str
case class Box(n: Int) derives Optics

val intToStr: Iso[Int, String] = Iso(_.toString, _.toInt)

val asStr: Lens[Box, String] = Optics[Box](_.n).imap(intToStr)
```

```scala marklit:extends=int-str
val box = Box(42)

println(asStr.get(box))                  // "42"
println(asStr.set(box, "100"))           // Box(100)
println(asStr.set(asStr.set(box, "7"), "100").n)  // 100 (set-set: last wins)
```

## `xmap`: re-type via inline functions

Same thing, but takes the `to` and `from` directly. `xmap(to, from)` is exactly `imap(Iso(to, from))`:

```scala marklit:extends=iso-base
case class Box(n: Int) derives Optics

val asStr = Optics[Box](_.n).xmap(_.toString, _.toInt)

println(asStr.get(Box(42)))        // "42"
println(asStr.set(Box(0), "100"))  // Box(100)
```

Use `xmap` for one-off transformations. Build an `Iso` value when you want to name and reuse the conversion.

## `Iso.id` and `reverse`

`Iso.id[A]` is the identity iso (`Iso(identity, identity)`). `iso.reverse` swaps `to` and `from`:

```scala marklit:extends=iso-base
val intToStr = Iso[Int, String](_.toString, _.toInt)
val strToInt = intToStr.reverse  // Iso[String, Int]

println(strToInt.to("42"))    // 42
println(strToInt.from(7))     // "7"
```

## Lens laws on a faithful iso

When `from(to(a)) == a` for all `a` and `to(from(b)) == b` for all `b`, the derived lens satisfies all three lens laws:

```scala marklit:silent,extends=iso-base,id=iso-laws
case class Box(n: Int) derives Optics

// Faithful iso on the full Int domain.
val asStr = Optics[Box](_.n).xmap(_.toString, _.toInt)

val box = Box(42)

val getSet = asStr.set(box, asStr.get(box)) == box
val setGet = asStr.get(asStr.set(box, "100")) == "100"
val setSet = asStr.set(asStr.set(box, "7"), "100") == asStr.set(box, "100")

println(s"get-set: $getSet")
println(s"set-get: $setGet")
println(s"set-set: $setSet")
```

```scala marklit:zio-app,extends=iso-laws
import zio.*
ZIO.unit
```

## When the iso isn't faithful

If `from(to(_))` isn't identity, `set-get` (in particular) breaks. Here's a deliberate counter-example:

```scala marklit:extends=iso-base
case class Box(s: String) derives Optics

// Lossy: to uppercases, from leaves alone. After set("hello"), get returns "HELLO".
val upper = Optics[Box](_.s).xmap(_.toUpperCase, identity)

val box = Box("hello")

// set-get fails: write "hello", read back "HELLO"
val setGet = upper.get(upper.set(box, "hello"))
println(s"set-get: ${setGet}")  // HELLO — not "hello"
```

This is a feature, not a bug. `Iso` is a typed contract that says "I promise these are inverses". When you violate the contract, the derived lens behaves predictably in the wrong way — never silently corrupts state. Pair `Iso` with a unit test on the `to`/`from` round-trip if the inverse isn't obvious.

## Use case: validated string

Conduit doesn't bundle "isos for common conversions" — they tend to be either trivial or lossy. But you can build them where they fit. Here's a custom iso for `Int` ↔ a non-empty digit-only string, with a domain restriction we enforce ourselves:

```scala marklit:silent,extends=iso-base,id=int-or
case class Form(value: Int) derives Optics

// Treat the int as a string in a form. Lawful only on inputs that round-trip
// (i.e. valid integer strings); the caller is on the hook for invalid input.
val asField: Lens[Form, String] = Optics[Form](_.value).xmap(_.toString, _.toInt)
```

```scala marklit:zio-app,extends=int-or,show-warnings=false
import zio.*

enum Op extends Action:
  case Edit(s: String)

val h: ActionHandler[Form, Form, Nothing] =
  handle[Form, Form, Nothing](Optics[Form]):
    case Op.Edit(s) =>
      // The form view is `String`; the model is `Int`. xmap bridges them.
      m => ZIO.succeed(ActionResult(asField.set(m, s)))

for
  c <- Conduit(Form(0))(h)
  _ <- c(Op.Edit("42"))
  _ <- c.run()
  s <- c.currentModel
  _ <- Console.printLine(s)
yield ()
```

This is the shape `xmap` is *for*: a lens viewing a value through a different runtime type without losing the ability to read or write through it. UI bindings, serialization round-trips, validated input fields.

## Inside a handler with `focus`

`xmap` composes with `focus` cleanly — re-bind to a sub-focus, then re-type:

```scala marklit:silent,extends=iso-base,id=focus-iso,show-warnings=false
import zio.*

case class Model(count: Int, label: String) derives Optics

enum Op extends Action:
  case SetCountStr(s: String)

val h: ActionHandler[Model, Model, Nothing] =
  handle[Model, Model, Nothing](Optics[Model]):
    case Op.SetCountStr(s) =>
      focus(_.count): // ambient: Lens[Model, Int]
        outer ?=>
          val asStr = outer.xmap[String](_.toString, _.toInt)
          m => ZIO.succeed(ActionResult(asStr.set(m, s)))
```

```scala marklit:zio-app,extends=focus-iso,show-warnings=false
for
  c <- Conduit(Model(0, "x"))(h)
  _ <- c(Op.SetCountStr("99"))
  _ <- c.run()
  s <- c.currentModel
  _ <- Console.printLine(s)
yield ()
```

## Where to next

- **[lenses-and-optics.md](lenses-and-optics.md)** — the macro that derives the base lenses you compose `xmap` with.
- **[collection-lenses.md](collection-lenses.md)** — the other extension that operates on lens receivers.
- **[handlers.md](handlers.md)** — using `focus` and lens-derived helpers inside action handlers.
