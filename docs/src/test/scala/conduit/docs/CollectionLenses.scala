package conduit.docs

import _root_.conduit.*
import _root_.conduit.CollectionLens.*
import ascent.*
import ascent.dsl.*
import specular.*
import specular.mermoid.Mermoid
import zio.test.*

object CollectionLenses extends DocSpec:

  case class Todo(text: String, done: Boolean) derives Optics
  case class Board(todos: Map[String, Todo]) derives Optics

  enum BoardOp extends Action:
    case Add(id: String, text: String)
    case Toggle(id: String)
    case Remove(id: String)

  private val handler: ActionHandler[Board, Board, Nothing] =
    handle[Board, Board, Nothing](Optics[Board]):
      case BoardOp.Add(id, text) =>
        focus(_.todos)(update(_ + (id -> Todo(text, false))))
      case BoardOp.Toggle(id) =>
        val cell = Optics[Board](_.todos).key(id)
        m =>
          val next = cell.get(m).map(t => cell.set(m, Some(t.copy(done = !t.done)))).getOrElse(m)
          zio.ZIO.succeed(ActionResult(next))
      case BoardOp.Remove(id) =>
        val cell = Optics[Board](_.todos).key(id)
        m => zio.ZIO.succeed(ActionResult(cell.set(m, None)))

  private val coll =
    """flowchart LR
      |  List[List at index] --> Opt[Option V]
      |  Vec[Vector atVector] --> Opt
      |  Map[Map key k] --> Opt
      |""".stripMargin

  def doc = page("Collection lenses")(
    md"""
The `Optics` macro cannot derive `xs(i)` or `m(k)`: those need a runtime value.
`CollectionLens` adds `at` / `atVector` / `key`, each focusing `Option[V]`.

- `Some(v)` in range updates; `Some(v)` at `index == size` **appends**
- `None` in range removes; out of range is a no-op
""",
    section("key on Map")(
      md"""
```scala
import conduit.CollectionLens.*

val milk = Optics[Board](_.todos).key("milk")
milk.get(board)            // Option[Todo]
milk.set(board, None)      // remove
milk.set(board, Some(t))   // upsert
```
""",
      example {
        Mermoid.diagram(coll)
      }.assert(ui => assertTrue(ui != null)),
      exampleValue {
        val milk  = Optics[Board](_.todos).key("milk")
        val start = Board(Map("milk" -> Todo("buy milk", false)))
        val done  = milk.get(start).map(t => milk.set(start, Some(t.copy(done = true)))).getOrElse(start)
        val gone  = milk.set(done, None)
        (milk.get(start).exists(!_.done), milk.get(done).exists(_.done), milk.get(gone).isEmpty)
      }.assert { case (a, b, c) =>
        assertTrue(a, b, c)
      },
    ),
    section("Live todos")(
      md"""
Add, toggle, and remove go through `key`. The list is a `Map[String, Todo]` so each row is
element-scoped: toggling one id does not notify a `squawkKey` on a sibling.
""",
      exampleIO {
        for
          (_, ctx) <- DocsRuntime.live(Board(Map("a" -> Todo("write tests", false))))(handler)
          todos    <- ctx.squawk(_.todos)
        yield E.div(
          E.button(Events.onClick(_ => ctx(BoardOp.Add("b", "ship docs"))), "Add 'ship docs'"),
          E.button(Events.onClick(_ => ctx(BoardOp.Toggle("a"))), "Toggle first"),
          E.button(Events.onClick(_ => ctx(BoardOp.Remove("a"))), "Remove first"),
          E.pre(
            todos.map { m =>
              if m.isEmpty then "(empty)"
              else
                m.toList
                  .sortBy(_._1)
                  .map { case (id, t) =>
                    s"$id: ${t.text} ${if t.done then "[done]" else "[todo]"}"
                  }
                  .mkString("\n")
            }
          ),
        )
      }.interactive,
    ),
    section("at / atVector")(
      md"""
Same `Option` protocol on `List` and `Vector`. `at(length)` with `Some(v)` appends, which is
how a lawful "write past the end" still satisfies set-get at that index.
""",
      exampleIO {
        Mermoid.diagramInteractive(coll, initialWidth = 560)
      }.interactive,
    ),
  )
end CollectionLenses
