package conduit

import zio.*
import zio.test.*

import conduit.AdvancedContextFunctions.*

object AdvancedContextFunctionsSpec extends ZIOSpecDefault:

  case class Address(city: String, zip: String) derives Optics
  case class User(name: String, age: Int, address: Address) derives Optics
  case class Model(user: User, count: Int) derives Optics
  case class IntBox(n: Int) derives Optics
  case class TwoFields(a: Int, b: String) derives Optics
  case class OptUser(name: Option[String]) derives Optics

  enum ForA extends Action:
    case Rename(s: String)
    case Move(c: String)
    case ResetCount
    case Known
    case Unknown
    case Inc
    case Dec

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AdvancedContextFunctions")(
      suite("when")(
        test("get returns Some when predicate holds"):
          given Lens[IntBox, Int]             = Optics[IntBox](_.n)
          val lens: Lens[IntBox, Option[Int]] = when(_ > 0)
          assertTrue(lens.get(IntBox(5)) == Some(5))
        ,
        test("get returns None when predicate fails"):
          given Lens[IntBox, Int]             = Optics[IntBox](_.n)
          val lens: Lens[IntBox, Option[Int]] = when(_ > 0)
          assertTrue(lens.get(IntBox(-1)) == None)
        ,
        test("set Some(valid) writes through"):
          given Lens[IntBox, Int]             = Optics[IntBox](_.n)
          val lens: Lens[IntBox, Option[Int]] = when(_ > 0)
          assertTrue(lens.set(IntBox(0), Some(5)) == IntBox(5))
        ,
        test("set Some(invalid) is no-op"):
          given Lens[IntBox, Int]             = Optics[IntBox](_.n)
          val lens: Lens[IntBox, Option[Int]] = when(_ > 0)
          assertTrue(lens.set(IntBox(5), Some(-1)) == IntBox(5))
        ,
        test("set None is no-op (no way to invent a missing value)"):
          given Lens[IntBox, Int]             = Optics[IntBox](_.n)
          val lens: Lens[IntBox, Option[Int]] = when(_ > 0)
          assertTrue(lens.set(IntBox(5), None) == IntBox(5)),
      ),
      suite("validated")(
        test("get returns Right when to() succeeds"):
          given Lens[IntBox, Int] = Optics[IntBox](_.n)
          val lens: Lens[IntBox, Either[String, String]] =
            validated[IntBox, Int, String, String](
              n => if n > 0 then Right(n.toString) else Left("non-positive"),
              _.toInt,
            )
          assertTrue(lens.get(IntBox(5)) == Right("5"))
        ,
        test("get returns Left when to() fails"):
          given Lens[IntBox, Int] = Optics[IntBox](_.n)
          val lens: Lens[IntBox, Either[String, String]] =
            validated[IntBox, Int, String, String](
              n => if n > 0 then Right(n.toString) else Left("non-positive"),
              _.toInt,
            )
          assertTrue(lens.get(IntBox(-1)) == Left("non-positive"))
        ,
        test("set Right(w) writes through via from()"):
          given Lens[IntBox, Int] = Optics[IntBox](_.n)
          val lens: Lens[IntBox, Either[String, String]] =
            validated[IntBox, Int, String, String](
              n => if n > 0 then Right(n.toString) else Left("non-positive"),
              _.toInt,
            )
          assertTrue(lens.set(IntBox(0), Right("42")) == IntBox(42))
        ,
        test("set Left(_) is no-op"):
          given Lens[IntBox, Int] = Optics[IntBox](_.n)
          val lens: Lens[IntBox, Either[String, String]] =
            validated[IntBox, Int, String, String](
              n => if n > 0 then Right(n.toString) else Left("bad"),
              _.toInt,
            )
          assertTrue(lens.set(IntBox(5), Left("anything")) == IntBox(5)),
      ),
      suite("multi")(
        test("get reads both lenses into a tuple"):
          val both = multi(Optics[TwoFields](_.a), Optics[TwoFields](_.b))
          assertTrue(both.get(TwoFields(1, "x")) == ((1, "x")))
        ,
        test("set writes both fields"):
          val both = multi(Optics[TwoFields](_.a), Optics[TwoFields](_.b))
          assertTrue(both.set(TwoFields(0, ""), (5, "ok")) == TwoFields(5, "ok"))
        ,
        test("set-get law"):
          val both = multi(Optics[TwoFields](_.a), Optics[TwoFields](_.b))
          assertTrue(both.get(both.set(TwoFields(0, ""), (5, "ok"))) == ((5, "ok")))
        ,
        test("get-set law (independent fields)"):
          val both = multi(Optics[TwoFields](_.a), Optics[TwoFields](_.b))
          val tf   = TwoFields(7, "z")
          assertTrue(both.set(tf, both.get(tf)) == tf),
      ),
      suite("navigate")(
        test("present path: get reads through"):
          given Lens[OptUser, Option[String]] = Optics[OptUser](_.name)
          val lens: Lens[OptUser, String] =
            navigate[OptUser, Option[String], String](
              path = identity,
              defaultValue = "anonymous",
              inverse = (_, w) => Some(w),
            )
          assertTrue(lens.get(OptUser(Some("alice"))) == "alice")
        ,
        test("absent path: get returns default"):
          given Lens[OptUser, Option[String]] = Optics[OptUser](_.name)
          val lens: Lens[OptUser, String] =
            navigate[OptUser, Option[String], String](
              path = identity,
              defaultValue = "anonymous",
              inverse = (_, w) => Some(w),
            )
          assertTrue(lens.get(OptUser(None)) == "anonymous")
        ,
        test("set writes through inverse"):
          given Lens[OptUser, Option[String]] = Optics[OptUser](_.name)
          val lens: Lens[OptUser, String] =
            navigate[OptUser, Option[String], String](
              path = identity,
              defaultValue = "anonymous",
              inverse = (_, w) => Some(w),
            )
          assertTrue(lens.set(OptUser(None), "bob") == OptUser(Some("bob")))
        ,
        test("set-get law on present branch"):
          given Lens[OptUser, Option[String]] = Optics[OptUser](_.name)
          val lens: Lens[OptUser, String] =
            navigate[OptUser, Option[String], String](
              path = identity,
              defaultValue = "anonymous",
              inverse = (_, w) => Some(w),
            )
          assertTrue(lens.get(lens.set(OptUser(None), "bob")) == "bob"),
      ),
      suite("For")(
        // Builder spares repetition: same Optics[Model] shared across many handlers.
        test("For.field builds a handler focused on a sub-field") {
          val handler: ActionHandler[Model, String, Nothing] =
            For[Model, Nothing].field(_.user.name) { case ForA.Rename(s) => updated(s) }

          val m0 = Model(User("Alice", 30, Address("NYC", "10001")), 0)
          for r <- handler.process(ForA.Rename("Bob"), m0)
          yield assertTrue(r.newModel.user.name == "Bob")
        },
        test("For.field works for deeply nested paths") {
          val handler: ActionHandler[Model, String, Nothing] =
            For[Model, Nothing].field(_.user.address.city) { case ForA.Move(c) => updated(c) }

          val m0 = Model(User("Alice", 30, Address("NYC", "10001")), 0)
          for r <- handler.process(ForA.Move("LA"), m0)
          yield assertTrue(r.newModel.user.address.city == "LA")
        },
        test("For.model builds a whole-model handler") {
          val handler: ActionHandler[Model, Model, Nothing] =
            For[Model, Nothing].model { case ForA.ResetCount => update(_.copy(count = 0)) }

          val m0 = Model(User("Alice", 30, Address("NYC", "10001")), 99)
          for r <- handler.process(ForA.ResetCount, m0)
          yield assertTrue(r.newModel.count == 0)
        },
        test("For: unhandled action produces a defect with E=Nothing (matches handle()'s contract)") {
          val handler: ActionHandler[Model, Int, Nothing] =
            For[Model, Nothing].field(_.count) { case ForA.Known => update(_ + 1) }

          val m0 = Model(User("a", 1, Address("c", "z")), 0)
          for exit <- handler.process(ForA.Unknown, m0).exit
          yield assertTrue(exit.isFailure)
        },
        test("For: handlers compose with >> like any other handler") {
          val For_ = For[Model, Nothing]
          val incH = For_.field(_.count) { case ForA.Inc => update(_ + 1) }
          val decH = For_.field(_.count) { case ForA.Dec => update(_ - 1) }
          val both = incH >> decH

          val m0 = Model(User("a", 1, Address("c", "z")), 5)
          for
            r1 <- both.process(ForA.Inc, m0)
            r2 <- both.process(ForA.Dec, m0)
          yield assertTrue(r1.newModel.count == 6, r2.newModel.count == 4)
        },
      ),
      suite("Compositions")(
        test("when after a derived sub-lens"):
          val nameLens: Lens[Model, String]         = Optics[Model](_.user.name)
          given Lens[Model, String]                 = nameLens
          val nonEmpty: Lens[Model, Option[String]] = when(_.nonEmpty)
          val m1                                    = Model(User("Alice", 30, Address("NYC", "10001")), 0)
          val m2                                    = Model(User("", 30, Address("NYC", "10001")), 0)
          assertTrue(
            nonEmpty.get(m1) == Some("Alice"),
            nonEmpty.get(m2) == None,
            nonEmpty.set(m2, Some("Bob")).user.name == "Bob",
            nonEmpty.set(m2, Some("")).user.name == "",
          )
        ,
        test("multi composes derived sub-lenses"):
          val both = multi(Optics[Model](_.user.name), Optics[Model](_.user.age))
          val m    = Model(User("Alice", 30, Address("NYC", "10001")), 0)
          assertTrue(
            both.get(m) == (("Alice", 30)),
            both.set(m, ("Bob", 99)).user == User("Bob", 99, Address("NYC", "10001")),
          ),
      ),
    )
  end spec
end AdvancedContextFunctionsSpec
