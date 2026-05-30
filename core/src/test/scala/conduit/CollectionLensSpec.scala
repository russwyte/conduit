package conduit

import zio.*
import zio.test.*

import conduit.CollectionLens.*

object CollectionLensSpec extends ZIOSpecDefault:

  case class OptBox(o: Option[Int]) derives Optics
  case class ListBox(xs: List[Int]) derives Optics
  case class MapBox(m: Map[String, Int]) derives Optics
  case class VecBox(v: Vector[Int]) derives Optics

  sealed trait Shape
  case class Circle(r: Int)    extends Shape
  case class Square(side: Int) extends Shape
  case class ShapeBox(s: Shape) derives Optics

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CollectionLens")(
      suite("Option extensions")(
        test("filterOption: get filters by predicate"):
          val lens = Optics[OptBox](_.o).filterOption(_ > 0)
          assertTrue(lens.get(OptBox(Some(5))) == Some(5), lens.get(OptBox(Some(-1))) == None)
        ,
        test("filterOption: get on None is None"):
          val lens = Optics[OptBox](_.o).filterOption(_ > 0)
          assertTrue(lens.get(OptBox(None)) == None)
        ,
        test("filterOption: set Some(valid) writes through"):
          val lens = Optics[OptBox](_.o).filterOption(_ > 0)
          assertTrue(lens.set(OptBox(None), Some(5)) == OptBox(Some(5)))
        ,
        test("filterOption: set Some(invalid) is no-op"):
          val lens = Optics[OptBox](_.o).filterOption(_ > 0)
          val box  = OptBox(Some(10))
          assertTrue(lens.set(box, Some(-1)) == box)
        ,
        test("filterOption: set None clears"):
          val lens = Optics[OptBox](_.o).filterOption(_ > 0)
          assertTrue(lens.set(OptBox(Some(5)), None) == OptBox(None))
        ,
        test("getOrElse: None reads default"):
          val lens = Optics[OptBox](_.o).getOrElse(0)
          assertTrue(lens.get(OptBox(None)) == 0)
        ,
        test("getOrElse: Some reads through"):
          val lens = Optics[OptBox](_.o).getOrElse(0)
          assertTrue(lens.get(OptBox(Some(5))) == 5)
        ,
        test("getOrElse: set writes Some, even if equal to default"):
          val lens = Optics[OptBox](_.o).getOrElse(0)
          assertTrue(lens.set(OptBox(None), 0) == OptBox(Some(0))),
      ),
      suite("List extensions")(
        test("filterList: get filters"):
          val lens = Optics[ListBox](_.xs).filterList(_ > 0)
          assertTrue(lens.get(ListBox(List(-1, 2, -3, 4))) == List(2, 4))
        ,
        test("filterList: set filters incoming"):
          val lens = Optics[ListBox](_.xs).filterList(_ > 0)
          assertTrue(lens.set(ListBox(Nil), List(-1, 2, -3)) == ListBox(List(2)))
        ,
        test("filterList: set with all-failing list clears"):
          val lens = Optics[ListBox](_.xs).filterList(_ > 0)
          assertTrue(lens.set(ListBox(List(1, 2)), List(-1, -2)) == ListBox(Nil))
        ,
        test("at: in-range get"):
          val lens = Optics[ListBox](_.xs).at(1)
          assertTrue(lens.get(ListBox(List(10, 20, 30))) == Some(20))
        ,
        test("at: out-of-bounds get is None"):
          val lens = Optics[ListBox](_.xs).at(5)
          assertTrue(lens.get(ListBox(List(1, 2))) == None)
        ,
        test("at: negative-index get is None"):
          val lens = Optics[ListBox](_.xs).at(-1)
          assertTrue(lens.get(ListBox(List(1, 2))) == None)
        ,
        test("at: get on empty list is None"):
          val lens = Optics[ListBox](_.xs).at(0)
          assertTrue(lens.get(ListBox(Nil)) == None)
        ,
        test("at: set Some at existing index updates"):
          val lens = Optics[ListBox](_.xs).at(1)
          assertTrue(lens.set(ListBox(List(1, 2, 3)), Some(99)) == ListBox(List(1, 99, 3)))
        ,
        test("at: set Some at index == length appends"):
          val lens = Optics[ListBox](_.xs).at(3)
          assertTrue(lens.set(ListBox(List(1, 2, 3)), Some(4)) == ListBox(List(1, 2, 3, 4)))
        ,
        test("at: set Some past end is no-op"):
          val lens = Optics[ListBox](_.xs).at(99)
          val box  = ListBox(List(1, 2, 3))
          assertTrue(lens.set(box, Some(0)) == box)
        ,
        test("at: set Some at negative index is no-op"):
          val lens = Optics[ListBox](_.xs).at(-1)
          val box  = ListBox(List(1, 2))
          assertTrue(lens.set(box, Some(99)) == box)
        ,
        test("at: set None at existing index removes"):
          val lens = Optics[ListBox](_.xs).at(1)
          assertTrue(lens.set(ListBox(List(1, 2, 3)), None) == ListBox(List(1, 3)))
        ,
        test("at: set None on out-of-bounds is no-op"):
          val lens = Optics[ListBox](_.xs).at(99)
          val box  = ListBox(List(1, 2))
          assertTrue(lens.set(box, None) == box)
        ,
        test("at: set Some(0) on empty list at index 0 appends"):
          val lens = Optics[ListBox](_.xs).at(0)
          assertTrue(lens.set(ListBox(Nil), Some(7)) == ListBox(List(7)))
        ,
        test("head: aliases at(0)"):
          val lens = Optics[ListBox](_.xs).head
          assertTrue(
            lens.get(ListBox(List(10, 20))) == Some(10),
            lens.set(ListBox(List(10, 20)), Some(99)) == ListBox(List(99, 20)),
            lens.set(ListBox(List(10, 20)), None) == ListBox(List(20)),
          )
        ,
        test("head on empty list is None"):
          val lens = Optics[ListBox](_.xs).head
          assertTrue(lens.get(ListBox(Nil)) == None)
        ,
        test("tail: get drops first"):
          val lens = Optics[ListBox](_.xs).tail
          assertTrue(lens.get(ListBox(List(1, 2, 3))) == List(2, 3))
        ,
        test("tail: get on empty list is empty"):
          val lens = Optics[ListBox](_.xs).tail
          assertTrue(lens.get(ListBox(Nil)) == Nil)
        ,
        test("tail: set preserves head"):
          val lens = Optics[ListBox](_.xs).tail
          assertTrue(lens.set(ListBox(List(1, 2, 3)), List(7, 8)) == ListBox(List(1, 7, 8)))
        ,
        test("tail: set on empty list yields the new tail as whole list"):
          val lens = Optics[ListBox](_.xs).tail
          assertTrue(lens.set(ListBox(Nil), List(9, 10)) == ListBox(List(9, 10)))
        ,
        test("at: works on a 1000-element list"):
          val big     = ListBox((1 to 1000).toList)
          val lens    = Optics[ListBox](_.xs).at(500)
          val updated = lens.set(big, Some(-1))
          assertTrue(lens.get(big) == Some(501), updated.xs(500) == -1, updated.xs.length == 1000),
      ),
      suite("Map extensions")(
        test("filterMap: get filters by tupled predicate"):
          val lens = Optics[MapBox](_.m).filterMap((_, v) => v > 1)
          assertTrue(lens.get(MapBox(Map("a" -> 1, "b" -> 2, "c" -> 3))) == Map("b" -> 2, "c" -> 3))
        ,
        test("filterMap: get on empty is empty"):
          val lens = Optics[MapBox](_.m).filterMap((_, _) => true)
          assertTrue(lens.get(MapBox(Map.empty)) == Map.empty[String, Int])
        ,
        test("filterMap: set writes input verbatim"):
          val lens = Optics[MapBox](_.m).filterMap((_, v) => v > 1)
          assertTrue(lens.set(MapBox(Map("a" -> 1)), Map("z" -> 9)) == MapBox(Map("z" -> 9)))
        ,
        test("key: get hit"):
          val lens = Optics[MapBox](_.m).key("a")
          assertTrue(lens.get(MapBox(Map("a" -> 1))) == Some(1))
        ,
        test("key: get miss is None"):
          val lens = Optics[MapBox](_.m).key("missing")
          assertTrue(lens.get(MapBox(Map("a" -> 1))) == None)
        ,
        test("key: get on empty map is None"):
          val lens = Optics[MapBox](_.m).key("a")
          assertTrue(lens.get(MapBox(Map.empty)) == None)
        ,
        test("key: set Some adds/updates"):
          val lens = Optics[MapBox](_.m).key("a")
          assertTrue(
            lens.set(MapBox(Map.empty), Some(1)) == MapBox(Map("a" -> 1)),
            lens.set(MapBox(Map("a" -> 1)), Some(2)) == MapBox(Map("a" -> 2)),
          )
        ,
        test("key: set None removes"):
          val lens = Optics[MapBox](_.m).key("a")
          assertTrue(lens.set(MapBox(Map("a" -> 1, "b" -> 2)), None) == MapBox(Map("b" -> 2)))
        ,
        test("key: set None on missing key is no-op"):
          val lens = Optics[MapBox](_.m).key("missing")
          val box  = MapBox(Map("a" -> 1))
          assertTrue(lens.set(box, None) == box)
        ,
        test("keys: get keyset"):
          val lens = Optics[MapBox](_.m).keys
          assertTrue(lens.get(MapBox(Map("a" -> 1, "b" -> 2))) == Set("a", "b"))
        ,
        test("keys: get on empty is empty"):
          val lens = Optics[MapBox](_.m).keys
          assertTrue(lens.get(MapBox(Map.empty)) == Set.empty[String])
        ,
        test("keys: set drops absent keys"):
          val lens = Optics[MapBox](_.m).keys
          assertTrue(lens.set(MapBox(Map("a" -> 1, "b" -> 2)), Set("a")) == MapBox(Map("a" -> 1)))
        ,
        test("keys: set with novel keys ignores them (no value to invent)"):
          val lens = Optics[MapBox](_.m).keys
          assertTrue(lens.set(MapBox(Map("a" -> 1)), Set("a", "z")) == MapBox(Map("a" -> 1)))
        ,
        test("keys: set empty clears"):
          val lens = Optics[MapBox](_.m).keys
          assertTrue(lens.set(MapBox(Map("a" -> 1, "b" -> 2)), Set.empty) == MapBox(Map.empty)),
      ),
      suite("Vector extensions")(
        test("atVector: in-range get"):
          val lens = Optics[VecBox](_.v).atVector(1)
          assertTrue(lens.get(VecBox(Vector(10, 20, 30))) == Some(20))
        ,
        test("atVector: out-of-bounds get is None"):
          val lens = Optics[VecBox](_.v).atVector(99)
          assertTrue(lens.get(VecBox(Vector(1, 2))) == None)
        ,
        test("atVector: negative-index get is None"):
          val lens = Optics[VecBox](_.v).atVector(-1)
          assertTrue(lens.get(VecBox(Vector(1, 2))) == None)
        ,
        test("atVector: get on empty is None"):
          val lens = Optics[VecBox](_.v).atVector(0)
          assertTrue(lens.get(VecBox(Vector.empty)) == None)
        ,
        test("atVector: set Some updates in-range"):
          val lens = Optics[VecBox](_.v).atVector(1)
          assertTrue(lens.set(VecBox(Vector(1, 2, 3)), Some(99)) == VecBox(Vector(1, 99, 3)))
        ,
        test("atVector: set Some at length appends"):
          val lens = Optics[VecBox](_.v).atVector(2)
          assertTrue(lens.set(VecBox(Vector(1, 2)), Some(3)) == VecBox(Vector(1, 2, 3)))
        ,
        test("atVector: set Some past end is no-op"):
          val lens = Optics[VecBox](_.v).atVector(99)
          val box  = VecBox(Vector(1, 2))
          assertTrue(lens.set(box, Some(0)) == box)
        ,
        test("atVector: set None removes"):
          val lens = Optics[VecBox](_.v).atVector(1)
          assertTrue(lens.set(VecBox(Vector(1, 2, 3)), None) == VecBox(Vector(1, 3)))
        ,
        test("atVector: set None out-of-bounds is no-op"):
          val lens = Optics[VecBox](_.v).atVector(99)
          val box  = VecBox(Vector(1, 2))
          assertTrue(lens.set(box, None) == box)
        ,
        test("atVector: set Some at negative index is no-op"):
          val lens = Optics[VecBox](_.v).atVector(-1)
          val box  = VecBox(Vector(1))
          assertTrue(lens.set(box, Some(0)) == box),
      ),
      suite("Traverse")(
        test("traverseList: all-success returns Right"):
          val tr =
            Traverse.traverseList(Optics[ListBox](_.xs))((n: Int) => if n > 0 then Right(n.toString) else Left("bad"))
          assertTrue(tr(ListBox(List(1, 2, 3))) == Right(List("1", "2", "3")))
        ,
        test("traverseList: short-circuits on first failure"):
          val tr =
            Traverse.traverseList(Optics[ListBox](_.xs))((n: Int) => if n > 0 then Right(n) else Left(s"bad: $n"))
          assertTrue(tr(ListBox(List(1, -2, 3, -4))) == Left("bad: -2"))
        ,
        test("traverseList: empty list returns Right(Nil)"):
          val tr = Traverse.traverseList(Optics[ListBox](_.xs))((n: Int) => Right(n))
          assertTrue(tr(ListBox(Nil)) == Right(Nil))
        ,
        test("traverseOption: None returns Right(None)"):
          val tr = Traverse.traverseOption(Optics[OptBox](_.o))((n: Int) => Right(n.toString))
          assertTrue(tr(OptBox(None)) == Right(None))
        ,
        test("traverseOption: Some(valid) returns Right(Some)"):
          val tr =
            Traverse.traverseOption(Optics[OptBox](_.o))((n: Int) => if n > 0 then Right(n.toString) else Left("bad"))
          assertTrue(tr(OptBox(Some(5))) == Right(Some("5")))
        ,
        test("traverseOption: Some(invalid) returns Left"):
          val tr =
            Traverse.traverseOption(Optics[OptBox](_.o))((n: Int) => if n > 0 then Right(n.toString) else Left("bad"))
          assertTrue(tr(OptBox(Some(-1))) == Left("bad")),
      ),
      suite("Prism")(
        test("partial: extract on matching case"):
          val circle = Prism.partial(Optics[ShapeBox](_.s))(
            { case Circle(r) => Some(r); case _ => None },
            (r: Int) => Circle(r),
          )
          assertTrue(circle.get(ShapeBox(Circle(5))) == Some(5))
        ,
        test("partial: extract on non-matching case is None"):
          val circle = Prism.partial(Optics[ShapeBox](_.s))(
            { case Circle(r) => Some(r); case _ => None },
            (r: Int) => Circle(r),
          )
          assertTrue(circle.get(ShapeBox(Square(7))) == None)
        ,
        test("partial: set Some(w) injects (overwrites foreign case)"):
          val circle = Prism.partial(Optics[ShapeBox](_.s))(
            { case Circle(r) => Some(r); case _ => None },
            (r: Int) => Circle(r),
          )
          assertTrue(circle.set(ShapeBox(Square(7)), Some(99)) == ShapeBox(Circle(99)))
        ,
        test("partial: set Some(w) on matching case updates"):
          val circle = Prism.partial(Optics[ShapeBox](_.s))(
            { case Circle(r) => Some(r); case _ => None },
            (r: Int) => Circle(r),
          )
          assertTrue(circle.set(ShapeBox(Circle(5)), Some(99)) == ShapeBox(Circle(99)))
        ,
        test("partial: set None is no-op (can't invent another case)"):
          val circle = Prism.partial(Optics[ShapeBox](_.s))(
            { case Circle(r) => Some(r); case _ => None },
            (r: Int) => Circle(r),
          )
          val box = ShapeBox(Square(7))
          assertTrue(circle.set(box, None) == box),
      ),
    )
  end spec
end CollectionLensSpec
