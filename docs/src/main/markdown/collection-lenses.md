# Collection Lenses

The `Optics` macro derives lenses for case-class **fields** — paths it can resolve at compile time. It can't, by design, derive lenses for `xs(i)`, `m(k)`, or any other path that depends on a runtime value. `CollectionLens` fills that gap with three primitives:

- `at(index)` for `Lens[M, List[V]]` → `Lens[M, Option[V]]`
- `atVector(index)` for `Lens[M, Vector[V]]` → `Lens[M, Option[V]]`
- `key(k)` for `Lens[M, Map[K, V]]` → `Lens[M, Option[V]]`

All three focus `Option[V]` so absence is first-class: `None` from `get` means "not present", `Some(v)` from `set` writes (or appends), `None` from `set` removes (or is a no-op when out of range).

## Importing

The extensions live in `conduit.CollectionLens`:

```scala marklit:top-level,id=cl-base
import conduit.*
import conduit.CollectionLens.*
import zio.*

case class Todo(text: String, done: Boolean) derives Optics
case class Settings(prefs: Map[String, Int]) derives Optics
case class App(todos: List[Todo], settings: Settings) derives Optics
```

## `at` for `List`

```scala marklit:extends=cl-base
val firstTodo: Lens[App, Option[Todo]] = Optics[App](_.todos).at(0)

val initial = App(
  todos    = List(Todo("buy milk", false), Todo("ship docs", false)),
  settings = Settings(Map.empty),
)

// get: in-range → Some
println(firstTodo.get(initial))  // Some(Todo(buy milk,false))

// get: out-of-range → None
println(Optics[App](_.todos).at(99).get(initial))  // None
```

### `set` semantics — every boundary

`at` was designed so the lens laws hold at every point and indexing past the end *appends*:

```scala marklit:extends=cl-base
val list = Optics[App](_.todos)

val a = App(List(Todo("a", false), Todo("b", false)), Settings(Map.empty))

// set Some(v) at in-range → update
println(list.at(0).set(a, Some(Todo("A", true))).todos.map(_.text))  // List(A, b)

// set Some(v) at index == size → APPEND (so the law set(get) == identity holds at the boundary)
println(list.at(2).set(a, Some(Todo("c", false))).todos.map(_.text))  // List(a, b, c)

// set Some(v) past size → no-op (would have to invent intermediate values)
println(list.at(99).set(a, Some(Todo("z", false))).todos.map(_.text)) // List(a, b)

// set None at in-range → remove
println(list.at(0).set(a, None).todos.map(_.text))                    // List(b)

// set None out of range → no-op
println(list.at(99).set(a, None).todos.map(_.text))                   // List(a, b)
```

The "append on equal-size" rule is intentional: it preserves the lens law `get(set(m, Some(x)))` == `Some(x)` at the boundary index. Without it, you couldn't write to "the next slot" in a single `set` call.

## `atVector` for `Vector`

Same shape and semantics as `at`, but for `Vector`. Different name because the macro infers the element type from the *receiver* — `Lens[M, List[V]]` and `Lens[M, Vector[V]]` are distinct receivers and overload resolution requires distinct extension method names:

```scala marklit:silent,id=cl-vec
import conduit.*
import conduit.CollectionLens.*

case class V(items: Vector[String]) derives Optics
val v = V(Vector("a", "b"))

println(Optics[V](_.items).atVector(0).get(v))                    // Some(a)
println(Optics[V](_.items).atVector(2).set(v, Some("c")).items)   // Vector(a, b, c)
```

## `key` for `Map`

```scala marklit:extends=cl-base
val fontSize: Lens[App, Option[Int]] = Optics[App](_.settings.prefs).key("font")

val a = App(Nil, Settings(Map("font" -> 12, "spacing" -> 4)))

// get: present → Some
println(fontSize.get(a))                                   // Some(12)

// get: absent → None
println(Optics[App](_.settings.prefs).key("nope").get(a))  // None

// set Some(v): adds or replaces
println(fontSize.set(a, Some(14)).settings.prefs)          // Map(font -> 14, spacing -> 4)
println(Optics[App](_.settings.prefs).key("new").set(a, Some(99)).settings.prefs)
                                                            // adds the new key

// set None: removes
println(fontSize.set(a, None).settings.prefs)              // Map(spacing -> 4)

// set None at absent key: no-op
println(Optics[App](_.settings.prefs).key("nope").set(a, None).settings.prefs == a.settings.prefs)
                                                            // true
```

## Composing with the macro

`CollectionLens` extensions take a `Lens[M, Coll[V]]` as the receiver, so the *base* lens can be anything the macro derives. Multi-step paths work:

```scala marklit:extends=cl-base
// Optics[App](_.todos).at(0) — list lens then element
val firstTodo = Optics[App](_.todos).at(0)
val a         = App(List(Todo("buy milk", false)), Settings(Map.empty))
println(firstTodo.get(a))

// Compose further: focus the `done` field of the first todo via the composed lens.
// Note: this only works for `set` if the element exists. `at(0)` returns
// Lens[App, Option[Todo]], not Lens[App, Todo] — there's no field-derivation
// macro through `Option`. Use the focused option directly instead:
val firstDone: App => Option[Boolean] = a => firstTodo.get(a).map(_.done)
println(firstDone(a))
```

If you need to *update* a field of the first list element, the idiomatic shape is `update` over the focused `Option[Todo]`:

```scala marklit:extends=cl-base
val firstTodo = Optics[App](_.todos).at(0)
val a         = App(List(Todo("buy milk", false)), Settings(Map.empty))

val updated = firstTodo.set(a, firstTodo.get(a).map(_.copy(done = true)))
println(updated.todos)
```

This is the spot where Conduit's lens macro's "no method calls in paths" constraint shows up. For element-level updates inside a collection, you read, transform, write — three explicit steps. The trade-off is correctness: the macro never invents a partial lens that silently no-ops.

## Inside a handler

The natural use is paired with `focus` ([handlers.md](handlers.md#focus)):

```scala marklit:top-level,id=cl-handler-defs
import conduit.*
import conduit.CollectionLens.*
import zio.*

case class Todo(text: String, done: Boolean) derives Optics
case class App(todos: List[Todo]) derives Optics

enum Op extends Action:
  case Add(t: Todo)
  case Remove(i: Int)
  case ToggleAt(i: Int)
```

```scala marklit:silent,extends=cl-handler-defs,id=cl-handler
val handler: ActionHandler[App, App, Nothing] =
  handle[App, App, Nothing](Optics[App]):
    case Op.Add(t) =>
      focus(_.todos)(update(_ :+ t))
    case Op.Remove(i) =>
      // at(i).set(_, None) removes
      m => ZIO.succeed(ActionResult(Optics[App](_.todos).at(i).set(m, None)))
    case Op.ToggleAt(i) =>
      // read-modify-write through at(i)
      m =>
        val lens = Optics[App](_.todos).at(i)
        ZIO.succeed(ActionResult(lens.set(m, lens.get(m).map(t => t.copy(done = !t.done)))))
```

```scala marklit:zio-app,extends=cl-handler
for
  c <- Conduit(App(Nil))(handler)
  _ <- c(Op.Add(Todo("buy milk", false)), Op.Add(Todo("ship docs", false)))
  _ <- c(Op.ToggleAt(1), Op.Remove(0))
  _ <- c.run()
  s <- c.currentModel
  _ <- Console.printLine(s.todos)
yield ()
```

## Lens laws — what's lawful and what's by design

Conduit's collection primitives are lawful at every defined point:

- **get-set:** `lens.set(m, lens.get(m)) == m` holds *everywhere*, including out-of-range indexes (because both sides are no-ops there).
- **set-get:** Holds for `Some(v)` at in-range and at index == size (the append case).
- **set-set:** Holds for in-range writes.

Out-of-bounds writes are no-ops *by design* — there's no value to invent for the "skipped" slots. This is the correct lens semantics for a partial focus: if the focus doesn't exist, neither does the write.

## Where to next

- **[lenses-and-optics.md](lenses-and-optics.md)** — the macro that derives field lenses.
- **[handlers.md](handlers.md)** — composing collection updates inside handlers via `focus`.
- **[iso.md](iso.md)** — re-typing a lens through an iso (e.g. `String` ↔ `URI`).
