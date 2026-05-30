package conduit

import zio.*
import zio.test.*

/** Tests for the `focus` context-function combinator: lens-law verification on the derived focus, ActionFunction
  * wiring (update / updated / noChange / noChange-with-followup / effectOnly), nested focus, error-channel
  * propagation, dirty-flag preservation, and composition with the existing handler combinators.
  */
object FocusSpec extends ZIOSpecDefault:

  // ── model ────────────────────────────────────────────────────────────────
  case class Address(city: String, zip: String) derives Optics
  case class User(name: String, age: Int, address: Address) derives Optics
  case class Model(user: User, count: Int, items: List[Int]) derives Optics

  // recursive struct for the same-type-field aliasing test
  case class Tree(value: Int, left: Option[Tree], right: Option[Tree]) derives Optics
  case class TreeBox(t: Tree) derives Optics

  enum A extends Action:
    case Rename(s: String)
    case Move(c: String)
    case Inc
    case AddItem(x: Int)
    case TouchOnly
    case TouchAndQueue
    case BumpEffect
    case Deep(s: String)
    case TreeLeftValue(v: Int)
    case Boom

  sealed trait Err
  case object Boomed extends Err

  // ── handler under test ───────────────────────────────────────────────────
  def modelHandler[E >: Err]: ActionHandler[Model, Model, E] =
    handle(Optics[Model]):
      case A.Rename(n)     => focus(_.user.name)(updated(n))
      case A.Move(c)       => focus(_.user.address.city)(updated(c))
      case A.Inc           => focus(_.count)(update(_ + 1))
      case A.AddItem(x)    => focus(_.items)(update(_ :+ x))
      case A.TouchOnly     => focus(_.count)(noChange)
      case A.TouchAndQueue => focus(_.count)(noChange[Model, E](A.Inc))
      case A.BumpEffect    =>
        // effectOnly takes the whole model; using it inside `focus` should still produce a clean result.
        focus(_.count)(effectOnly(_ => ZIO.unit))
      case A.Deep(s) =>
        // nested focus: focus(_.user) then focus(_.address.city)
        focus(_.user):
          focus(_.address.city)(updated(s))
      case A.Boom => _ => ZIO.fail(Boomed)

  // ── lens-law helpers ─────────────────────────────────────────────────────
  def lensLaws[M, V](lens: Lens[M, V], m: M, vNew: V, vOther: V): TestResult =
    val getSet = lens.set(m, lens.get(m)) == m
    val setGet = lens.get(lens.set(m, vNew)) == vNew
    val setSet = lens.set(lens.set(m, vOther), vNew) == lens.set(m, vNew)
    assertTrue(getSet) && assertTrue(setGet) && assertTrue(setSet)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("focus")(
      // ─── derived-lens laws ──────────────────────────────────────────────
      suite("derived lens laws")(
        test("focus(_.f): single field"):
          val m = Model(User("alice", 30, Address("NYC", "10001")), 0, Nil)
          lensLaws(Optics[Model](_.count), m, 42, -7)
        ,
        test("focus(_.a.b.c): deep field"):
          val m = Model(User("alice", 30, Address("NYC", "10001")), 0, Nil)
          lensLaws(Optics[Model](_.user.address.city), m, "LA", "SF")
        ,
        test("focus on a recursive struct's field doesn't collide with same-named inner field"):
          val inner = Tree(1, None, None)
          val outer = Tree(0, Some(inner), None)
          val box   = TreeBox(outer)
          val lens  = Optics[TreeBox](_.t.left)
          assertTrue(lens.get(box) == Some(inner))
          && assertTrue(lens.set(box, None).t.left == None)
          // inner tree's `left` is None — the macro must not have walked into it
          && assertTrue(lens.set(box, None).t.right == None)
        ,
      ),
      // ─── ActionFunction wiring ──────────────────────────────────────────
      suite("ActionFunction wiring")(
        test("focus(updated) writes the focused field, leaves siblings"):
          val initial = Model(User("alice", 30, Address("NYC", "10001")), 5, List(1))
          for r <- modelHandler[Err].process(A.Rename("bob"), initial)
          yield assertTrue(r.newModel.user.name == "bob")
            && assertTrue(r.newModel.user.age == 30)
            && assertTrue(r.newModel.user.address == initial.user.address)
            && assertTrue(r.newModel.count == initial.count)
            && assertTrue(r.newModel.items == initial.items)
            && assertTrue(r.dirty)
        ,
        test("focus(update) reads + transforms + writes"):
          val initial = Model(User("alice", 30, Address("NYC", "10001")), 5, Nil)
          for r <- modelHandler[Err].process(A.Inc, initial)
          yield assertTrue(r.newModel.count == 6) && assertTrue(r.dirty)
        ,
        test("focus on a list field with `update(_ :+ x)`"):
          val initial = Model(User("alice", 30, Address("NYC", "10001")), 0, List(1, 2))
          for r <- modelHandler[Err].process(A.AddItem(3), initial)
          yield assertTrue(r.newModel.items == List(1, 2, 3))
        ,
        test("focus(noChange) → clean, model untouched"):
          val initial = Model(User("alice", 30, Address("NYC", "10001")), 7, Nil)
          for r <- modelHandler[Err].process(A.TouchOnly, initial)
          yield assertTrue(r.newModel == initial) && assertTrue(!r.dirty)
        ,
        test("focus(noChange(followUp)) propagates `next`"):
          val initial = Model(User("alice", 30, Address("NYC", "10001")), 0, Nil)
          for r <- modelHandler[Err].process(A.TouchAndQueue, initial)
          yield assertTrue(r.newModel == initial)
            && assertTrue(!r.dirty)
            && assertTrue(r.next.contains(A.Inc))
        ,
        test("focus(effectOnly) leaves model clean"):
          val initial = Model(User("alice", 30, Address("NYC", "10001")), 9, Nil)
          for r <- modelHandler[Err].process(A.BumpEffect, initial)
          yield assertTrue(r.newModel == initial) && assertTrue(!r.dirty)
        ,
        test("error: ZIO.fail in body surfaces unchanged"):
          val initial = Model(User("alice", 30, Address("NYC", "10001")), 0, Nil)
          for exit <- modelHandler[Err].process(A.Boom, initial).either
          yield assertTrue(exit == Left(Boomed))
        ,
      ),
      // ─── nested focus ───────────────────────────────────────────────────
      suite("nested focus")(
        test("focus(_.a)(focus(_.b.c)(...)) ≡ focus(_.a.b.c)(...)"):
          val initial = Model(User("alice", 30, Address("NYC", "10001")), 0, Nil)
          for
            r1 <- modelHandler[Err].process(A.Move("LA"), initial)     // direct deep path
            r2 <- modelHandler[Err].process(A.Deep("LA"), initial)     // nested focus
          yield assertTrue(r1.newModel == r2.newModel)
            && assertTrue(r1.newModel.user.address.city == "LA")
        ,
      ),
      // ─── composition with existing handler combinators ──────────────────
      suite("composition")(
        test("`>>` (orElse): two focus-built handlers in one chain"):
          val h1 = handle[Model, Model, Err](Optics[Model]):
            case A.Rename(n) => focus(_.user.name)(updated(n))
          val h2 = handle[Model, Model, Err](Optics[Model]):
            case A.Inc => focus(_.count)(update(_ + 1))
          val composed = h1 >> h2
          val initial  = Model(User("alice", 30, Address("NYC", "10001")), 5, Nil)
          for
            r1 <- composed.process(A.Rename("bob"), initial)
            r2 <- composed.process(A.Inc, initial)
          yield assertTrue(r1.newModel.user.name == "bob")
            && assertTrue(r2.newModel.count == 6)
        ,
        test("`onUnhandled` works on a focus-built handler"):
          val h = handle[Model, Model, Err](Optics[Model]):
            case A.Rename(n) => focus(_.user.name)(updated(n))
          val withErr = h.onUnhandled(_ => Boomed)
          val initial = Model(User("alice", 30, Address("NYC", "10001")), 0, Nil)
          for r <- withErr.process(A.Inc, initial).either
          yield assertTrue(r == Left(Boomed))
        ,
        test("`widen` works on a focus-built handler"):
          val h: ActionHandler[Model, Model, Nothing] =
            handle[Model, Model, Nothing](Optics[Model]):
              case A.Inc => focus(_.count)(update(_ + 1))
          val widened: ActionHandler[Model, Model, Err] = h.widen[Err]
          val initial                                   = Model(User("alice", 30, Address("NYC", "10001")), 0, Nil)
          for r <- widened.process(A.Inc, initial)
          yield assertTrue(r.newModel.count == 1)
        ,
      ),
      // ─── property-based ────────────────────────────────────────────────
      suite("property: lens laws hold for focus(_.field)")(
        test("on `count` over arbitrary models and values"):
          val genName     = Gen.alphaNumericStringBounded(0, 8)
          val genAge      = Gen.int(0, 120)
          val genCity     = Gen.alphaNumericStringBounded(0, 8)
          val genZip      = Gen.alphaNumericStringBounded(0, 8)
          val genAddress  = (genCity <*> genZip).map((c, z) => Address(c, z))
          val genUser     = (genName <*> genAge <*> genAddress).map((n, a, ad) => User(n, a, ad))
          val genItems    = Gen.listOfBounded(0, 4)(Gen.int)
          val genCount    = Gen.int
          val genModel    = (genUser <*> genCount <*> genItems).map((u, c, is) => Model(u, c, is))
          val genVNew     = Gen.int
          val genVOther   = Gen.int
          val countLens   = Optics[Model](_.count)
          check(genModel, genVNew, genVOther) { (m, vNew, vOther) =>
            lensLaws(countLens, m, vNew, vOther)
          }
        ,
        test("on `user.name` over arbitrary models and values"):
          val genName     = Gen.alphaNumericStringBounded(0, 8)
          val genAge      = Gen.int(0, 120)
          val genCity     = Gen.alphaNumericStringBounded(0, 8)
          val genZip      = Gen.alphaNumericStringBounded(0, 8)
          val genAddress  = (genCity <*> genZip).map((c, z) => Address(c, z))
          val genUser     = (genName <*> genAge <*> genAddress).map((n, a, ad) => User(n, a, ad))
          val genItems    = Gen.listOfBounded(0, 4)(Gen.int)
          val genCount    = Gen.int
          val genModel    = (genUser <*> genCount <*> genItems).map((u, c, is) => Model(u, c, is))
          val genStrNew   = Gen.alphaNumericStringBounded(0, 8)
          val genStrOther = Gen.alphaNumericStringBounded(0, 8)
          val nameLens    = Optics[Model](_.user.name)
          check(genModel, genStrNew, genStrOther) { (m, vNew, vOther) =>
            lensLaws(nameLens, m, vNew, vOther)
          },
      ),
    )
end FocusSpec
