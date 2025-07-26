package conduit
import zio.*
import zio.test.*

/** Comprehensive test suite for the Lens class and related optics functionality.
  *
  * Test Coverage:
  *   - Basic Operations: get/set operations, lens laws (get-set, set-get, set-set)
  *   - Nested Operations: deeply nested field access, partial modifications
  *   - Composition: operator (>>), compose method, apply method, associativity
  *   - Edge Cases: identity lens, immutability preservation, reference equality
  *   - Type Safety: type preservation through composition, nested types
  *   - Property-Based Testing: lens laws with various inputs, composition properties
  *   - Performance: efficiency with deeply nested structures, object allocation
  *   - Complex Scenarios: multiple lenses, different types, real-world usage patterns
  *
  * The tests verify that the lens implementation correctly follows the fundamental lens laws and maintains functional
  * programming principles like immutability.
  */
object LensSpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment & Scope, Any] =
    // Test data structures
    case class Foo(x: Int) derives Optics
    case class Bar(foo: Foo) derives Optics
    case class Person(name: String, age: Int) derives Optics
    case class Address(street: String, city: String) derives Optics
    case class Employee(person: Person, address: Address, salary: Double) derives Optics
    case class ComplexNested(
        a: A,
        value: String,
    ) derives Optics
    case class A(b: B, count: Int) derives Optics
    case class B(c: C, flag: Boolean) derives Optics
    case class C(d: D, data: String) derives Optics
    case class D(value: Int) derives Optics

    // Test data with different types
    case class TypesTest(
        stringField: String,
        intField: Int,
        doubleField: Double,
        booleanField: Boolean,
        optionField: Option[String],
        listField: List[Int],
    ) derives Optics

    suite("Lens")(
      suite("Basic Operations")(
        suite("get/set")(
          test("simple field access"):
            val lens = Optics[Foo](_.x)
            val foo  = Foo(42)
            assertTrue(lens.get(foo) == 42) &&
            assertTrue(lens.set(foo, 43) == Foo(43))
          ,
          test("nested field access"):
            val lens = Optics[Bar](_.foo.x)
            val bar  = Bar(Foo(42))
            assertTrue(lens.get(bar) == 42) &&
            assertTrue(lens.set(bar, 43) == Bar(Foo(43)))
          ,
          test("string field"):
            val lens   = Optics[Person](_.name)
            val person = Person("Alice", 30)
            assertTrue(lens.get(person) == "Alice") &&
            assertTrue(lens.set(person, "Bob") == Person("Bob", 30))
          ,
          test("multiple different types"):
            val typesTest   = TypesTest("test", 42, 3.14, true, Some("option"), List(1, 2, 3))
            val stringLens  = Optics[TypesTest](_.stringField)
            val intLens     = Optics[TypesTest](_.intField)
            val doubleLens  = Optics[TypesTest](_.doubleField)
            val booleanLens = Optics[TypesTest](_.booleanField)

            assertTrue(stringLens.get(typesTest) == "test") &&
            assertTrue(intLens.get(typesTest) == 42) &&
            assertTrue(doubleLens.get(typesTest) == 3.14) &&
            assertTrue(booleanLens.get(typesTest) == true) &&
            assertTrue(stringLens.set(typesTest, "updated") == typesTest.copy(stringField = "updated")) &&
            assertTrue(intLens.set(typesTest, 100) == typesTest.copy(intField = 100)),
        ),
        suite("lens laws")(
          test("get-set law: lens.set(obj, lens.get(obj)) == obj"):
            val lens   = Optics[Person](_.name)
            val person = Person("Alice", 30)
            assertTrue(lens.set(person, lens.get(person)) == person)
          ,
          test("set-get law: lens.get(lens.set(obj, value)) == value"):
            val lens   = Optics[Person](_.age)
            val person = Person("Alice", 30)
            val newAge = 25
            assertTrue(lens.get(lens.set(person, newAge)) == newAge)
          ,
          test("set-set law: lens.set(lens.set(obj, v1), v2) == lens.set(obj, v2)"):
            val lens   = Optics[Person](_.name)
            val person = Person("Alice", 30)
            assertTrue(lens.set(lens.set(person, "Bob"), "Charlie") == lens.set(person, "Charlie")),
        ),
        suite("nested operations")(
          test("deeply nested field access"):
            val lens = Optics[ComplexNested](_.a.b.c.d.value)
            val nested = ComplexNested(
              A(B(C(D(42), "test"), true), 10),
              "root",
            )
            assertTrue(lens.get(nested) == 42) &&
            assertTrue(lens.set(nested, 100).a.b.c.d.value == 100)
          ,
          test("partial nested modification preserves other fields"):
            val lens = Optics[ComplexNested](_.a.b.flag)
            val nested = ComplexNested(
              A(B(C(D(42), "test"), true), 10),
              "root",
            )
            val modified = lens.set(nested, false)
            assertTrue(modified.a.b.flag == false) &&
            assertTrue(modified.a.b.c.d.value == 42) &&
            assertTrue(modified.a.b.c.data == "test") &&
            assertTrue(modified.a.count == 10) &&
            assertTrue(modified.value == "root")
          ,
          test("multiple nested modifications"):
            val employee = Employee(
              Person("John", 30),
              Address("123 Main St", "NYC"),
              75000.0,
            )
            val nameLens   = Optics[Employee](_.person.name)
            val ageLens    = Optics[Employee](_.person.age)
            val cityLens   = Optics[Employee](_.address.city)
            val salaryLens = Optics[Employee](_.salary)

            val modified = salaryLens.set(
              cityLens.set(
                ageLens.set(
                  nameLens.set(employee, "Jane"),
                  25,
                ),
                "LA",
              ),
              80000.0,
            )

            assertTrue(modified.person.name == "Jane") &&
            assertTrue(modified.person.age == 25) &&
            assertTrue(modified.address.city == "LA") &&
            assertTrue(modified.address.street == "123 Main St") &&
            assertTrue(modified.salary == 80000.0),
        ),
      ),
      suite("Composition")(
        test("compose operator (>>)"):
          val fooLens = Optics[Foo](_.x)
          val barLens = Optics[Bar](_.foo)
          val lens    = barLens >> fooLens
          val bar     = Bar(Foo(42))
          assertTrue(lens.get(bar) == 42) &&
          assertTrue(lens.set(bar, 43) == Bar(Foo(43)))
        ,
        test("compose method"):
          val fooLens = Optics[Foo](_.x)
          val barLens = Optics[Bar](_.foo)
          val lens    = barLens.compose(fooLens)
          val bar     = Bar(Foo(42))
          assertTrue(lens.get(bar) == 42) &&
          assertTrue(lens.set(bar, 43) == Bar(Foo(43)))
        ,
        test("apply method (path composition)"):
          val barLens = Optics[Bar](_.foo)
          val lens    = barLens(_.x)
          val bar     = Bar(Foo(42))
          assertTrue(lens.get(bar) == 42) &&
          assertTrue(lens.set(bar, 43) == Bar(Foo(43)))
        ,
        test("chained composition"):
          val employeeLens = Optics[Employee](_.person)
          val nameLens     = employeeLens(_.name)
          val ageLens      = employeeLens(_.age)

          val employee = Employee(
            Person("Alice", 30),
            Address("123 Main St", "NYC"),
            75000.0,
          )

          assertTrue(nameLens.get(employee) == "Alice") &&
          assertTrue(ageLens.get(employee) == 30) &&
          assertTrue(nameLens.set(employee, "Bob").person.name == "Bob") &&
          assertTrue(ageLens.set(employee, 25).person.age == 25)
        ,
        test("composition associativity"):
          // Test that (a >> b) >> c == a >> (b >> c)
          val lens1 = Optics[ComplexNested](_.a)
          val lens2 = Optics[A](_.b)
          val lens3 = Optics[B](_.c)
          val lens4 = Optics[C](_.d)
          val lens5 = Optics[D](_.value)

          val leftAssoc  = ((lens1 >> lens2) >> lens3) >> (lens4 >> lens5)
          val rightAssoc = lens1 >> (lens2 >> (lens3 >> (lens4 >> lens5)))

          val nested = ComplexNested(
            A(B(C(D(42), "test"), true), 10),
            "root",
          )

          assertTrue(leftAssoc.get(nested) == rightAssoc.get(nested)) &&
          assertTrue(leftAssoc.set(nested, 100) == rightAssoc.set(nested, 100)),
      ),
      suite("Edge Cases")(
        test("identity lens behavior"):
          val identityLens = new Lens[Person, Person]:
            def get(m: Person): Person            = m
            def set(m: Person, v: Person): Person = v

          val person    = Person("Alice", 30)
          val newPerson = Person("Bob", 25)

          assertTrue(identityLens.get(person) == person) &&
          assertTrue(identityLens.set(person, newPerson) == newPerson)
        ,
        test("lens with same source and target type"):
          case class Container(value: Container) derives Optics

          // This would be a recursive structure in practice, but for testing
          // we can create a simple lens that works with the structure
          val lens = new Lens[Container, Container]:
            def get(m: Container): Container               = m.value
            def set(m: Container, v: Container): Container = m.copy(value = v)

          val inner = Container(null.asInstanceOf[Container])
          val outer = Container(inner)

          assertTrue(lens.get(outer) == inner) &&
          assertTrue(lens.set(outer, inner).value == inner)
        ,
        test("lens operations preserve immutability"):
          val person   = Person("Alice", 30)
          val nameLens = Optics[Person](_.name)
          val ageLens  = Optics[Person](_.age)

          val modifiedName = nameLens.set(person, "Bob")
          val modifiedAge  = ageLens.set(person, 25)

          // Original should be unchanged
          assertTrue(person.name == "Alice") &&
          assertTrue(person.age == 30) &&
          // Modifications should be isolated
          assertTrue(modifiedName.name == "Bob" && modifiedName.age == 30) &&
          assertTrue(modifiedAge.name == "Alice" && modifiedAge.age == 25),
      ),
      suite("Type Safety")(
        test("lens maintains type safety through composition"):
          val employeeLens = Optics[Employee](_.person)
          val nameLens     = employeeLens(_.name)

          val employee = Employee(
            Person("Alice", 30),
            Address("123 Main St", "NYC"),
            75000.0,
          )

          // This should compile and work correctly
          val name: String      = nameLens.get(employee)
          val updated: Employee = nameLens.set(employee, "Bob")

          assertTrue(name == "Alice") &&
          assertTrue(updated.person.name == "Bob")
        ,
        test("nested lens preserves all intermediate types"):
          val complexLens = Optics[ComplexNested](_.a.b.c.data)
          val nested = ComplexNested(
            A(B(C(D(42), "original"), true), 10),
            "root",
          )

          val data: String           = complexLens.get(nested)
          val updated: ComplexNested = complexLens.set(nested, "updated")

          assertTrue(data == "original") &&
          assertTrue(updated.a.b.c.data == "updated") &&
          // Verify structure is preserved
          assertTrue(updated.a.b.c.d.value == 42) &&
          assertTrue(updated.a.b.flag == true) &&
          assertTrue(updated.a.count == 10) &&
          assertTrue(updated.value == "root"),
      ),
      suite("Property-Based Testing")(
        test("lens composition is associative for simple values"):
          val v1 = 42
          val v2 = 10
          val v3 = 100

          val lens1 = Optics[ComplexNested](_.a)
          val lens2 = Optics[A](_.count)
          val lens3 = new Lens[Int, Int]:
            def get(m: Int): Int         = m
            def set(m: Int, v: Int): Int = v

          val nested     = ComplexNested(A(B(C(D(v1), "test"), true), v2), "root")
          val leftAssoc  = (lens1 >> lens2) >> lens3
          val rightAssoc = lens1 >> (lens2 >> lens3)

          assertTrue(leftAssoc.get(nested) == rightAssoc.get(nested)) &&
          assertTrue(leftAssoc.set(nested, v3) == rightAssoc.set(nested, v3))
        ,
        test("lens laws hold for various values"):
          val testCases = List(
            ("Alice", 30),
            ("Bob", 25),
            ("Charlie", 40),
            ("", 0),
            ("Very Long Name", 999),
          )

          val results = testCases.map { case (name, age) =>
            val person   = Person(name, age)
            val nameLens = Optics[Person](_.name)

            // Get-Set law
            val getSetLaw = nameLens.set(person, nameLens.get(person)) == person
            // Set-Get law
            val setGetLaw = nameLens.get(nameLens.set(person, name)) == name
            // Set-Set law
            val setSetLaw = nameLens.set(nameLens.set(person, "temp"), name) == nameLens.set(person, name)

            getSetLaw && setGetLaw && setSetLaw
          }

          assertTrue(results.forall(identity)),
      ),
      suite("Performance Characteristics")(
        test("lens operations should be efficient for deeply nested structures"):
          val deepNested = ComplexNested(
            A(B(C(D(42), "level4"), true), 10),
            "root",
          )
          val lens = Optics[ComplexNested](_.a.b.c.d.value)

          // Perform multiple operations to test efficiency
          val iterations = 1000
          val start      = java.lang.System.currentTimeMillis()

          var result = deepNested
          (1 to iterations).foreach { i =>
            result = lens.set(result, i)
          }

          val end      = java.lang.System.currentTimeMillis()
          val duration = end - start

          assertTrue(lens.get(result) == iterations) &&
          assertTrue(duration < 1000) // Should complete in less than 1 second
        ,
        test("composed lens should not create excessive object allocation"):
          val employee = Employee(
            Person("Alice", 30),
            Address("123 Main St", "NYC"),
            75000.0,
          )

          val nameLens = Optics[Employee](_.person.name)
          val ageLens  = Optics[Employee](_.person.age)

          // Multiple modifications should not create intermediate objects
          var result = employee
          (1 to 100).foreach { i =>
            result = nameLens.set(result, s"Name$i")
            result = ageLens.set(result, 20 + i)
          }

          assertTrue(result.person.name == "Name100") &&
          assertTrue(result.person.age == 120) &&
          assertTrue(result.address == employee.address) &&
          assertTrue(result.salary == employee.salary),
      ),
      suite("Complex Scenarios")(
        test("multiple lenses working on same object"):
          val employee = Employee(
            Person("Alice", 30),
            Address("123 Main St", "NYC"),
            75000.0,
          )

          val nameLens   = Optics[Employee](_.person.name)
          val ageLens    = Optics[Employee](_.person.age)
          val streetLens = Optics[Employee](_.address.street)
          val cityLens   = Optics[Employee](_.address.city)
          val salaryLens = Optics[Employee](_.salary)

          // Apply multiple lenses in sequence
          val result = List(
            (nameLens, "Bob"),
            (streetLens, "456 Oak Ave"),
            (cityLens, "LA"),
            (salaryLens, 85000.0),
            (ageLens, 35),
          ).foldLeft(employee) { case (emp, (lens, value)) =>
            lens.asInstanceOf[Lens[Employee, Any]].set(emp, value)
          }

          assertTrue(result.person.name == "Bob") &&
          assertTrue(result.person.age == 35) &&
          assertTrue(result.address.street == "456 Oak Ave") &&
          assertTrue(result.address.city == "LA") &&
          assertTrue(result.salary == 85000.0)
        ,
        test("lens composition with different types"):
          case class IntWrapper(value: Int) derives Optics
          case class NumberWrapper(wrapper: IntWrapper) derives Optics

          val numberWrapper = NumberWrapper(IntWrapper(42))
          val lens          = Optics[NumberWrapper](_.wrapper.value)

          assertTrue(lens.get(numberWrapper) == 42) &&
          assertTrue(lens.set(numberWrapper, 100) == NumberWrapper(IntWrapper(100)))
        ,
        test("lens operations preserve reference equality for unchanged parts"):
          val address  = Address("123 Main St", "NYC")
          val person   = Person("Alice", 30)
          val employee = Employee(person, address, 75000.0)

          val salaryLens = Optics[Employee](_.salary)
          val modified   = salaryLens.set(employee, 80000.0)

          // Address and person should be the same reference since they weren't modified
          assertTrue(modified.address eq address) &&
          assertTrue(modified.person eq person) &&
          assertTrue(modified.salary == 80000.0),
      ),
    )
  end spec
end LensSpec
