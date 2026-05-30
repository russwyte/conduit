package conduit

import zio.*
import zio.test.*

import conduit.ActionHandlerCollections.*

object ActionHandlerCollectionsSpec extends ZIOSpecDefault:

  case class OptModel(o: Option[Int]) derives Optics
  case class ListModel(xs: List[Int]) derives Optics
  case class VecModel(v: Vector[Int]) derives Optics
  case class MapModel(m: Map[String, Int]) derives Optics
  case class IntModel(n: Int) derives Optics

  // Helper: run an ActionFunction directly without going through a full handler.
  def run[M, E](af: ActionFunction[M, E])(m: M): IO[E, ActionResult[M, E]] = af(m)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ActionHandlerCollections")(
      suite("Option")(
        test("updateOption: Some(x) → Some(f(x))"):
          given Lens[OptModel, Option[Int]] = Optics[OptModel](_.o)
          for r <- run(updateOption[OptModel, Int, Nothing](_ + 1))(OptModel(Some(5)))
          yield assertTrue(r.newModel == OptModel(Some(6)), r.dirty)
        ,
        test("updateOption: None stays None"):
          given Lens[OptModel, Option[Int]] = Optics[OptModel](_.o)
          for r <- run(updateOption[OptModel, Int, Nothing](_ + 1))(OptModel(None))
          yield assertTrue(r.newModel == OptModel(None))
        ,
        test("setSome writes Some(value)"):
          given Lens[OptModel, Option[Int]] = Optics[OptModel](_.o)
          for r <- run(setSome[OptModel, Int, Nothing](42))(OptModel(None))
          yield assertTrue(r.newModel == OptModel(Some(42)))
        ,
        test("setNone clears existing Some"):
          given Lens[OptModel, Option[Int]] = Optics[OptModel](_.o)
          for r <- run(setNone[OptModel, Int, Nothing])(OptModel(Some(5)))
          yield assertTrue(r.newModel == OptModel(None))
        ,
        test("setNone on already-None is still a (vacuous) write"):
          given Lens[OptModel, Option[Int]] = Optics[OptModel](_.o)
          for r <- run(setNone[OptModel, Int, Nothing])(OptModel(None))
          yield assertTrue(r.newModel == OptModel(None))
        ,
        test("filterOptionAction: predicate fails clears Some"):
          given Lens[OptModel, Option[Int]] = Optics[OptModel](_.o)
          for r <- run(filterOptionAction[OptModel, Int, Nothing](_ > 0))(OptModel(Some(-1)))
          yield assertTrue(r.newModel == OptModel(None))
        ,
        test("filterOptionAction: predicate holds keeps Some"):
          given Lens[OptModel, Option[Int]] = Optics[OptModel](_.o)
          for r <- run(filterOptionAction[OptModel, Int, Nothing](_ > 0))(OptModel(Some(5)))
          yield assertTrue(r.newModel == OptModel(Some(5)))
        ,
        test("orElseOption: None gets default"):
          given Lens[OptModel, Option[Int]] = Optics[OptModel](_.o)
          for r <- run(orElseOption[OptModel, Int, Nothing](Some(10)))(OptModel(None))
          yield assertTrue(r.newModel == OptModel(Some(10)))
        ,
        test("orElseOption: Some unchanged"):
          given Lens[OptModel, Option[Int]] = Optics[OptModel](_.o)
          for r <- run(orElseOption[OptModel, Int, Nothing](Some(10)))(OptModel(Some(5)))
          yield assertTrue(r.newModel == OptModel(Some(5))),
      ),
      suite("List")(
        test("updateList maps each element"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(updateList[ListModel, Int, Nothing](_ * 2))(ListModel(List(1, 2, 3)))
          yield assertTrue(r.newModel == ListModel(List(2, 4, 6)))
        ,
        test("updateList: empty list stays empty"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(updateList[ListModel, Int, Nothing](_ * 2))(ListModel(Nil))
          yield assertTrue(r.newModel == ListModel(Nil))
        ,
        test("filterListAction filters"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(filterListAction[ListModel, Int, Nothing](_ > 0))(ListModel(List(-1, 2, -3, 4)))
          yield assertTrue(r.newModel == ListModel(List(2, 4)))
        ,
        test("appendToList: zero items still produces a write (concat with empty)"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(appendToList[ListModel, Int, Nothing]())(ListModel(List(1)))
          yield assertTrue(r.newModel == ListModel(List(1)))
        ,
        test("appendToList: many items"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(appendToList[ListModel, Int, Nothing](2, 3))(ListModel(List(1)))
          yield assertTrue(r.newModel == ListModel(List(1, 2, 3)))
        ,
        test("prependToList prepends in given order"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(prependToList[ListModel, Int, Nothing](2, 3))(ListModel(List(99)))
          yield assertTrue(r.newModel == ListModel(List(2, 3, 99)))
        ,
        test("removeFromList removes all occurrences"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(removeFromList[ListModel, Int, Nothing](2))(ListModel(List(1, 2, 3, 2, 4)))
          yield assertTrue(r.newModel == ListModel(List(1, 3, 4)))
        ,
        test("removeFromList: missing element is no-op-by-content (still a write)"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(removeFromList[ListModel, Int, Nothing](99))(ListModel(List(1, 2)))
          yield assertTrue(r.newModel == ListModel(List(1, 2)))
        ,
        test("updateAtListIndex: in range updates"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(updateAtListIndex[ListModel, Int, Nothing](1, 99))(ListModel(List(1, 2, 3)))
          yield assertTrue(r.newModel == ListModel(List(1, 99, 3)), r.dirty)
        ,
        test("updateAtListIndex: out of range → clean (untouched, dirty=false)"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(updateAtListIndex[ListModel, Int, Nothing](99, 0))(ListModel(List(1, 2)))
          yield assertTrue(r.newModel == ListModel(List(1, 2)), !r.dirty)
        ,
        test("updateAtListIndex: negative index → clean"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(updateAtListIndex[ListModel, Int, Nothing](-1, 0))(ListModel(List(1, 2)))
          yield assertTrue(r.newModel == ListModel(List(1, 2)), !r.dirty)
        ,
        test("updateAtListIndex: empty list → clean"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(updateAtListIndex[ListModel, Int, Nothing](0, 0))(ListModel(Nil))
          yield assertTrue(r.newModel == ListModel(Nil), !r.dirty),
      ),
      suite("Vector")(
        test("updateVector maps elements"):
          given Lens[VecModel, Vector[Int]] = Optics[VecModel](_.v)
          for r <- run(updateVector[VecModel, Int, Nothing](_ * 2))(VecModel(Vector(1, 2, 3)))
          yield assertTrue(r.newModel == VecModel(Vector(2, 4, 6)))
        ,
        test("filterVectorAction filters"):
          given Lens[VecModel, Vector[Int]] = Optics[VecModel](_.v)
          for r <- run(filterVectorAction[VecModel, Int, Nothing](_ > 0))(VecModel(Vector(-1, 2, -3)))
          yield assertTrue(r.newModel == VecModel(Vector(2)))
        ,
        test("appendToVector"):
          given Lens[VecModel, Vector[Int]] = Optics[VecModel](_.v)
          for r <- run(appendToVector[VecModel, Int, Nothing](2, 3))(VecModel(Vector(1)))
          yield assertTrue(r.newModel == VecModel(Vector(1, 2, 3)))
        ,
        test("prependToVector"):
          given Lens[VecModel, Vector[Int]] = Optics[VecModel](_.v)
          for r <- run(prependToVector[VecModel, Int, Nothing](2, 3))(VecModel(Vector(99)))
          yield assertTrue(r.newModel == VecModel(Vector(2, 3, 99)))
        ,
        test("updateAtVectorIndex: in range updates"):
          given Lens[VecModel, Vector[Int]] = Optics[VecModel](_.v)
          for r <- run(updateAtVectorIndex[VecModel, Int, Nothing](1, 99))(VecModel(Vector(1, 2, 3)))
          yield assertTrue(r.newModel == VecModel(Vector(1, 99, 3)), r.dirty)
        ,
        test("updateAtVectorIndex: out of range → clean"):
          given Lens[VecModel, Vector[Int]] = Optics[VecModel](_.v)
          for r <- run(updateAtVectorIndex[VecModel, Int, Nothing](99, 0))(VecModel(Vector(1, 2)))
          yield assertTrue(!r.dirty)
        ,
        test("updateAtVectorIndex: negative → clean"):
          given Lens[VecModel, Vector[Int]] = Optics[VecModel](_.v)
          for r <- run(updateAtVectorIndex[VecModel, Int, Nothing](-1, 0))(VecModel(Vector(1)))
          yield assertTrue(!r.dirty),
      ),
      suite("Map")(
        test("updateMapValues maps values"):
          given Lens[MapModel, Map[String, Int]] = Optics[MapModel](_.m)
          for r <- run(updateMapValues[MapModel, String, Int, Nothing](_ * 2))(MapModel(Map("a" -> 1, "b" -> 2)))
          yield assertTrue(r.newModel == MapModel(Map("a" -> 2, "b" -> 4)))
        ,
        test("filterMapValues filters by value predicate"):
          given Lens[MapModel, Map[String, Int]] = Optics[MapModel](_.m)
          for r <- run(filterMapValues[MapModel, String, Int, Nothing](_ > 1))(MapModel(Map("a" -> 1, "b" -> 2)))
          yield assertTrue(r.newModel == MapModel(Map("b" -> 2)))
        ,
        test("putInMap adds new key"):
          given Lens[MapModel, Map[String, Int]] = Optics[MapModel](_.m)
          for r <- run(putInMap[MapModel, String, Int, Nothing]("z", 99))(MapModel(Map("a" -> 1)))
          yield assertTrue(r.newModel == MapModel(Map("a" -> 1, "z" -> 99)))
        ,
        test("putInMap overwrites existing"):
          given Lens[MapModel, Map[String, Int]] = Optics[MapModel](_.m)
          for r <- run(putInMap[MapModel, String, Int, Nothing]("a", 99))(MapModel(Map("a" -> 1)))
          yield assertTrue(r.newModel == MapModel(Map("a" -> 99)))
        ,
        test("removeFromMap removes existing"):
          given Lens[MapModel, Map[String, Int]] = Optics[MapModel](_.m)
          for r <- run(removeFromMap[MapModel, String, Int, Nothing]("a"))(MapModel(Map("a" -> 1, "b" -> 2)))
          yield assertTrue(r.newModel == MapModel(Map("b" -> 2)))
        ,
        test("removeFromMap on missing key writes through (still dirty)"):
          given Lens[MapModel, Map[String, Int]] = Optics[MapModel](_.m)
          for r <- run(removeFromMap[MapModel, String, Int, Nothing]("z"))(MapModel(Map("a" -> 1)))
          yield assertTrue(r.newModel == MapModel(Map("a" -> 1)))
        ,
        test("updateAtMapKey: existing key applies f"):
          given Lens[MapModel, Map[String, Int]] = Optics[MapModel](_.m)
          for r <- run(updateAtMapKey[MapModel, String, Int, Nothing]("a", _ + 100))(MapModel(Map("a" -> 1)))
          yield assertTrue(r.newModel == MapModel(Map("a" -> 101)), r.dirty)
        ,
        test("updateAtMapKey: missing key → clean"):
          given Lens[MapModel, Map[String, Int]] = Optics[MapModel](_.m)
          for r <- run(updateAtMapKey[MapModel, String, Int, Nothing]("missing", _ + 1))(MapModel(Map("a" -> 1)))
          yield assertTrue(!r.dirty, r.newModel == MapModel(Map("a" -> 1))),
      ),
      suite("Generic context-function patterns")(
        test("whenCondition: predicate holds → applies op"):
          given Lens[IntModel, Int] = Optics[IntModel](_.n)
          for r <- run(whenCondition[IntModel, Int, Nothing](_ > 0)(_ * 2))(IntModel(5))
          yield assertTrue(r.newModel == IntModel(10), r.dirty)
        ,
        test("whenCondition: predicate fails → clean"):
          given Lens[IntModel, Int] = Optics[IntModel](_.n)
          for r <- run(whenCondition[IntModel, Int, Nothing](_ > 0)(_ * 2))(IntModel(-1))
          yield assertTrue(!r.dirty, r.newModel == IntModel(-1))
        ,
        test("safeTransform: Right writes through"):
          given Lens[IntModel, Int] = Optics[IntModel](_.n)
          for r <- run(safeTransform[IntModel, Int, String, Nothing](n => Right(n + 1)))(IntModel(5))
          yield assertTrue(r.newModel == IntModel(6))
        ,
        test("safeTransform: Left → clean"):
          given Lens[IntModel, Int] = Optics[IntModel](_.n)
          for r <- run(safeTransform[IntModel, Int, String, Nothing](_ => Left("nope")))(IntModel(5))
          yield assertTrue(!r.dirty, r.newModel == IntModel(5))
        ,
        test("sequenceOps: applies each op left-to-right"):
          given Lens[IntModel, Int] = Optics[IntModel](_.n)
          val ops: List[Int => Int] = List(_ + 1, _ * 10, _ - 5)
          for r <- run(sequenceOps[IntModel, Int, Nothing](ops))(IntModel(2))
            // ((2 + 1) * 10) - 5 = 25
          yield assertTrue(r.newModel == IntModel(25))
        ,
        test("sequenceOps: empty ops still writes (identity)"):
          given Lens[IntModel, Int] = Optics[IntModel](_.n)
          for r <- run(sequenceOps[IntModel, Int, Nothing](Nil))(IntModel(5))
          yield assertTrue(r.newModel == IntModel(5), r.dirty)
        ,
        test("findAndUpdate: hit replaces all matching elements"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(findAndUpdate[ListModel, Int, Nothing](_.find(_ == 2))(_ * 100))(ListModel(List(1, 2, 3, 2)))
          yield assertTrue(r.newModel == ListModel(List(1, 200, 3, 200)), r.dirty)
        ,
        test("findAndUpdate: miss → clean"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(findAndUpdate[ListModel, Int, Nothing](_.find(_ == 99))(_ * 100))(ListModel(List(1, 2)))
          yield assertTrue(!r.dirty, r.newModel == ListModel(List(1, 2)))
        ,
        test("batchOperation: condition holds applies op"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(batchOperation[ListModel, Int, Nothing](_.length > 1)(_.reverse))(ListModel(List(1, 2, 3)))
          yield assertTrue(r.newModel == ListModel(List(3, 2, 1)))
        ,
        test("batchOperation: condition fails → clean"):
          given Lens[ListModel, List[Int]] = Optics[ListModel](_.xs)
          for r <- run(batchOperation[ListModel, Int, Nothing](_.length > 10)(_.reverse))(ListModel(List(1, 2, 3)))
          yield assertTrue(!r.dirty, r.newModel == ListModel(List(1, 2, 3))),
      ),
      suite("Integration with handle()")(
        // Verify these helpers compose cleanly into a real ActionHandler
        test("handle uses appendToList through the lens context") {
          enum A extends Action:
            case Add(x: Int)

          val h = handle[ListModel, List[Int], Nothing](Optics[ListModel](_.xs)):
            case A.Add(x) => appendToList(x)

          for r <- h.process(A.Add(99), ListModel(List(1, 2)))
          yield assertTrue(r.newModel == ListModel(List(1, 2, 99)))
        },
        test("updateAtMapKey returns clean inside a handler when key absent") {
          enum A extends Action:
            case Bump(key: String)

          val h = handle[MapModel, Map[String, Int], Nothing](Optics[MapModel](_.m)):
            case A.Bump(k) => updateAtMapKey[MapModel, String, Int, Nothing](k, _ + 1)

          for r <- h.process(A.Bump("missing"), MapModel(Map("a" -> 1)))
          yield assertTrue(!r.dirty, r.newModel == MapModel(Map("a" -> 1)))
        },
      ),
    )
  end spec
end ActionHandlerCollectionsSpec
