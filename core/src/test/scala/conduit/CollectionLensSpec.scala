package conduit

import zio.*
import zio.test.*

import conduit.CollectionLens.*

/** Tests for [[CollectionLens]] — `at` (List), `atVector` (Vector), `key` (Map). Per primitive: get, set Some at every
  * boundary, set None at every boundary, lens laws (get-set, set-get, set-set), composition, and a property test
  * spanning the index range.
  */
object CollectionLensSpec extends ZIOSpecDefault:

  case class ListBox(xs: List[Int]) derives Optics
  case class VecBox(xs: Vector[Int]) derives Optics
  case class MapBox(m: Map[String, Int]) derives Optics

  // For composition tests: a deeper model whose path needs the macro before the collection extension.
  case class User(name: String, tags: List[String]) derives Optics
  case class Group(owner: User) derives Optics

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CollectionLens")(
      // ─── List.at ────────────────────────────────────────────────────────
      suite("List.at")(
        test("get: in-range"):
          val lens = Optics[ListBox](_.xs).at(1)
          assertTrue(lens.get(ListBox(List(10, 20, 30))) == Some(20))
        ,
        test("get: index == size is None"):
          val lens = Optics[ListBox](_.xs).at(3)
          assertTrue(lens.get(ListBox(List(10, 20, 30))) == None)
        ,
        test("get: past-size is None"):
          val lens = Optics[ListBox](_.xs).at(99)
          assertTrue(lens.get(ListBox(List(1, 2))) == None)
        ,
        test("get: negative is None"):
          val lens = Optics[ListBox](_.xs).at(-1)
          assertTrue(lens.get(ListBox(List(1, 2))) == None)
        ,
        test("get: empty is None"):
          val lens = Optics[ListBox](_.xs).at(0)
          assertTrue(lens.get(ListBox(Nil)) == None)
        ,
        test("set Some(v): in-range updates"):
          val lens = Optics[ListBox](_.xs).at(1)
          assertTrue(lens.set(ListBox(List(10, 20, 30)), Some(99)) == ListBox(List(10, 99, 30)))
        ,
        test("set Some(v): index == size appends"):
          val lens = Optics[ListBox](_.xs).at(2)
          assertTrue(lens.set(ListBox(List(10, 20)), Some(30)) == ListBox(List(10, 20, 30)))
        ,
        test("set Some(v): empty list at index 0 appends"):
          val lens = Optics[ListBox](_.xs).at(0)
          assertTrue(lens.set(ListBox(Nil), Some(7)) == ListBox(List(7)))
        ,
        test("set Some(v): past-size is no-op"):
          val lens = Optics[ListBox](_.xs).at(5)
          val box  = ListBox(List(1, 2))
          assertTrue(lens.set(box, Some(99)) == box)
        ,
        test("set Some(v): negative is no-op"):
          val lens = Optics[ListBox](_.xs).at(-1)
          val box  = ListBox(List(1, 2))
          assertTrue(lens.set(box, Some(99)) == box)
        ,
        test("set None: in-range removes"):
          val lens = Optics[ListBox](_.xs).at(1)
          assertTrue(lens.set(ListBox(List(10, 20, 30)), None) == ListBox(List(10, 30)))
        ,
        test("set None: at index == size is no-op (preserves get-set on the absence)"):
          val lens = Optics[ListBox](_.xs).at(2)
          val box  = ListBox(List(10, 20))
          assertTrue(lens.set(box, None) == box)
        ,
        test("set None: past-size is no-op"):
          val lens = Optics[ListBox](_.xs).at(99)
          val box  = ListBox(List(1, 2))
          assertTrue(lens.set(box, None) == box)
        ,
        test("set None: negative is no-op"):
          val lens = Optics[ListBox](_.xs).at(-1)
          val box  = ListBox(List(1, 2))
          assertTrue(lens.set(box, None) == box)
        ,
        // ── lens laws ─────────────────────────────────────────────────
        test("law: get-set holds for in-range, equal-size, past, negative, empty"):
          val xs = List(10, 20, 30)
          val cases = List(
            (Optics[ListBox](_.xs).at(0), ListBox(xs)),
            (Optics[ListBox](_.xs).at(1), ListBox(xs)),
            (Optics[ListBox](_.xs).at(2), ListBox(xs)),
            (Optics[ListBox](_.xs).at(3), ListBox(xs)),  // == size
            (Optics[ListBox](_.xs).at(99), ListBox(xs)), // past
            (Optics[ListBox](_.xs).at(-1), ListBox(xs)), // negative
            (Optics[ListBox](_.xs).at(0), ListBox(Nil)), // empty
          )
          assertTrue(cases.forall((lens, m) => lens.set(m, lens.get(m)) == m))
        ,
        test("law: set-get for Some(v) at in-range and at index == size"):
          val lensIn  = Optics[ListBox](_.xs).at(1)
          val lensEnd = Optics[ListBox](_.xs).at(3)
          val box     = ListBox(List(10, 20, 30))
          assertTrue(lensIn.get(lensIn.set(box, Some(99))) == Some(99))
          && assertTrue(lensEnd.get(lensEnd.set(box, Some(99))) == Some(99))
        ,
        test("law: set-set for in-range and equal-size"):
          val lensIn  = Optics[ListBox](_.xs).at(1)
          val lensEnd = Optics[ListBox](_.xs).at(3)
          val box     = ListBox(List(10, 20, 30))
          assertTrue(lensIn.set(lensIn.set(box, Some(7)), Some(99)) == lensIn.set(box, Some(99)))
          && assertTrue(lensEnd.set(lensEnd.set(box, Some(7)), Some(99)) == lensEnd.set(box, Some(99))),
      ),
      // ─── Vector.atVector ────────────────────────────────────────────────
      suite("Vector.atVector")(
        test("get: in-range"):
          val lens = Optics[VecBox](_.xs).atVector(1)
          assertTrue(lens.get(VecBox(Vector(10, 20, 30))) == Some(20))
        ,
        test("set Some(v): in-range updates"):
          val lens = Optics[VecBox](_.xs).atVector(1)
          assertTrue(lens.set(VecBox(Vector(10, 20, 30)), Some(99)) == VecBox(Vector(10, 99, 30)))
        ,
        test("set Some(v): index == size appends"):
          val lens = Optics[VecBox](_.xs).atVector(2)
          assertTrue(lens.set(VecBox(Vector(10, 20)), Some(30)) == VecBox(Vector(10, 20, 30)))
        ,
        test("set None: in-range removes"):
          val lens = Optics[VecBox](_.xs).atVector(1)
          assertTrue(lens.set(VecBox(Vector(10, 20, 30)), None) == VecBox(Vector(10, 30)))
        ,
        test("set Some(v): past-size is no-op; set None at out-of-range is no-op"):
          val lensPast = Optics[VecBox](_.xs).atVector(5)
          val lensNeg  = Optics[VecBox](_.xs).atVector(-1)
          val box      = VecBox(Vector(1, 2))
          assertTrue(lensPast.set(box, Some(99)) == box)
          && assertTrue(lensNeg.set(box, Some(99)) == box)
          && assertTrue(lensPast.set(box, None) == box)
          && assertTrue(lensNeg.set(box, None) == box)
        ,
        test("law: lens laws on the Vector primitive (in-range and equal-size)"):
          val lensIn  = Optics[VecBox](_.xs).atVector(1)
          val lensEnd = Optics[VecBox](_.xs).atVector(3)
          val box     = VecBox(Vector(10, 20, 30))
          val getSet  = lensIn.set(box, lensIn.get(box)) == box
          val setGet  = lensIn.get(lensIn.set(box, Some(99))) == Some(99)
          val setSet  = lensIn.set(lensIn.set(box, Some(7)), Some(99)) == lensIn.set(box, Some(99))
          val endLaw  = lensEnd.set(box, lensEnd.get(box)) == box
          assertTrue(getSet) && assertTrue(setGet) && assertTrue(setSet) && assertTrue(endLaw),
      ),
      // ─── Map.key ────────────────────────────────────────────────────────
      suite("Map.key")(
        test("get: present"):
          val lens = Optics[MapBox](_.m).key("a")
          assertTrue(lens.get(MapBox(Map("a" -> 1, "b" -> 2))) == Some(1))
        ,
        test("get: absent is None"):
          val lens = Optics[MapBox](_.m).key("z")
          assertTrue(lens.get(MapBox(Map("a" -> 1))) == None)
        ,
        test("set Some(v): replaces present"):
          val lens = Optics[MapBox](_.m).key("a")
          assertTrue(lens.set(MapBox(Map("a" -> 1, "b" -> 2)), Some(99)) == MapBox(Map("a" -> 99, "b" -> 2)))
        ,
        test("set Some(v): adds when absent"):
          val lens = Optics[MapBox](_.m).key("c")
          assertTrue(lens.set(MapBox(Map("a" -> 1)), Some(3)) == MapBox(Map("a" -> 1, "c" -> 3)))
        ,
        test("set None: removes when present"):
          val lens = Optics[MapBox](_.m).key("a")
          assertTrue(lens.set(MapBox(Map("a" -> 1, "b" -> 2)), None) == MapBox(Map("b" -> 2)))
        ,
        test("set None: when absent the model is unchanged (==)"):
          val lens = Optics[MapBox](_.m).key("z")
          val box  = MapBox(Map("a" -> 1))
          assertTrue(lens.set(box, None) == box)
        ,
        test("law: get-set / set-get / set-set at present and absent keys"):
          val lensA = Optics[MapBox](_.m).key("a")
          val lensZ = Optics[MapBox](_.m).key("z")
          val box   = MapBox(Map("a" -> 1, "b" -> 2))
          assertTrue(lensA.set(box, lensA.get(box)) == box)
          && assertTrue(lensZ.set(box, lensZ.get(box)) == box)
          && assertTrue(lensA.get(lensA.set(box, Some(99))) == Some(99))
          && assertTrue(lensZ.get(lensZ.set(box, Some(99))) == Some(99))
          && assertTrue(lensA.set(lensA.set(box, Some(7)), Some(99)) == lensA.set(box, Some(99)))
          && assertTrue(lensZ.set(lensZ.set(box, Some(7)), Some(99)) == lensZ.set(box, Some(99))),
      ),
      // ─── composition ────────────────────────────────────────────────────
      suite("composition")(
        test("through a multi-step macro path: Optics[Group](_.owner.tags).at(0)"):
          val lens = Optics[Group](_.owner.tags).at(0)
          val g    = Group(User("alice", List("scala", "zio")))
          assertTrue(lens.get(g) == Some("scala"))
          && assertTrue(lens.set(g, Some("zoom")).owner.tags == List("zoom", "zio"))
          && assertTrue(lens.set(g, None).owner.tags == List("zio"))
        ,
        test("Map.key composes with macro path"):
          case class Settings(prefs: Map[String, Int]) derives Optics
          case class App(settings: Settings) derives Optics
          val lens = Optics[App](_.settings.prefs).key("font")
          val app  = App(Settings(Map("font" -> 12)))
          assertTrue(lens.get(app) == Some(12))
          && assertTrue(lens.set(app, Some(14)).settings.prefs == Map("font" -> 14))
          && assertTrue(lens.set(app, None).settings.prefs == Map.empty),
      ),
      // ─── property: lens laws over arbitrary lists/indexes ───────────────
      suite("property")(
        test("List.at: get-set holds for any list of length 0..5 and any index in -2..7"):
          val genXs    = Gen.listOfBounded(0, 5)(Gen.int(0, 100))
          val genIndex = Gen.int(-2, 7)
          check(genXs, genIndex) { (xs, i) =>
            val lens = Optics[ListBox](_.xs).at(i)
            val box  = ListBox(xs)
            assertTrue(lens.set(box, lens.get(box)) == box)
          }
        ,
        test("List.at: set-get holds for in-range and equal-size with arbitrary v"):
          val genXs = Gen.listOfBounded(1, 5)(Gen.int(0, 100))
          val genV  = Gen.int(0, 100)
          check(genXs, genV) { (xs, v) =>
            // pick an index in [0, xs.length] (inclusive end → append case)
            val results = (0 to xs.length).toList.map { i =>
              val lens = Optics[ListBox](_.xs).at(i)
              val box  = ListBox(xs)
              lens.get(lens.set(box, Some(v))) == Some(v)
            }
            assertTrue(results.forall(identity))
          }
        ,
        test("Map.key: lens laws hold for any key (present or absent)"):
          val genMap = Gen.mapOfBounded(0, 5)(Gen.alphaNumericStringBounded(1, 4), Gen.int(0, 100))
          val genKey = Gen.alphaNumericStringBounded(1, 4)
          val genV   = Gen.int(0, 100)
          check(genMap, genKey, genV) { (m, k, v) =>
            val lens   = Optics[MapBox](_.m).key(k)
            val box    = MapBox(m)
            val getSet = lens.set(box, lens.get(box)) == box
            val setGet = lens.get(lens.set(box, Some(v))) == Some(v)
            val setSet = lens.set(lens.set(box, Some(v)), Some(v + 1)) == lens.set(box, Some(v + 1))
            assertTrue(getSet) && assertTrue(setGet) && assertTrue(setSet)
          },
      ),
    )
end CollectionLensSpec
