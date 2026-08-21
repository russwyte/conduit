package conduit.docs

import _root_.conduit.*
import specular.*
import specular.mermoid.Mermoid
import zio.test.*

object LensesAndOptics extends DocSpec:

  case class Address(city: String, zip: String) derives Optics
  case class User(name: String, age: Int, address: Address) derives Optics
  case class Model(user: User, count: Int) derives Optics

  private val compose =
    """flowchart LR
      |  M[Model] --> U[user]
      |  U --> A[address]
      |  A --> C[city]
      |""".stripMargin

  def doc = page("Lenses and optics")(
    md"""
A `Lens[M, V]` is `get` + `set` on an immutable model. `derives Optics` plus
`Optics[M](_.field.nested)` builds that lens at compile time from a field-selection path.
No reflection. Unmodified sibling trees keep the same reference, which is what
[FastEq](fast-equality.html) `withReferenceEquality` exploits.
""",
    section("Derive a path")(
      md"""
```scala
case class Address(city: String, zip: String) derives Optics
case class User(name: String, age: Int, address: Address) derives Optics
case class Model(user: User, count: Int) derives Optics

val city = Optics[Model](_.user.address.city)
```

The path must be a chain of field selects. Method calls and `match` are compile errors.
""",
      exampleValue {
        val city  = Optics[Model](_.user.address.city)
        val start = Model(User("Ada", 36, Address("NYC", "10001")), 0)
        val moved = city.set(start, "Boston")
        (city.get(start), city.get(moved), moved.user.name)
      }.assert { case (from, to, name) =>
        assertTrue(from == "NYC", to == "Boston", name == "Ada")
      },
      example {
        Mermoid.diagram(compose)
      }.assert(ui => assertTrue(ui != null)),
    ),
    section("What the macro writes")(
      md"""
`Optics[Model](_.user.address.city)` is the same `copy` chain you would type by hand:

```scala
new Lens[Model, String]:
  def get(m: Model) = m.user.address.city
  def set(m: Model, v: String) =
    m.copy(user = m.user.copy(address = m.user.address.copy(city = v)))
```
"""
    ),
    section("Compose")(
      md"""
`>>` (also `compose`) turns `Lens[A, B]` and `Lens[B, C]` into `Lens[A, C]`. Nested
`Optics[M](_.a.b)` is that composition, inlined.
""",
      exampleValue {
        val user  = Optics[Model](_.user)
        val city  = Optics[User](_.address.city)
        val path  = user >> city
        val start = Model(User("Ada", 36, Address("NYC", "10001")), 0)
        path.get(start)
      }.assert(city => assertTrue(city == "NYC")),
      exampleIO {
        Mermoid.diagramInteractive(compose, initialWidth = 560)
      }.interactive,
    ),
    section("Laws")(
      md"""
For a lawful lens: get-set is identity, set-get returns the written value, set-set keeps the
last write. Collection helpers in [Collection lenses](collection-lenses.html) are lawful at
every *defined* index; out-of-range writes are no-ops.
"""
    ),
  )
end LensesAndOptics
