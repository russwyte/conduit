package conduit

import zio.*
import zio.test.*

import conduit.BidirectionalLens.*
import conduit.BidirectionalLens.Collections.*
import conduit.BidirectionalLens.Validation.*

object BidirectionalLensSpec extends ZIOSpecDefault:

  case class Box[A](value: A)
  case class StrBox(s: String) derives Optics
  case class ListBox(xs: List[Int]) derives Optics
  case class OptBox(o: Option[Int]) derives Optics
  case class MapBox(m: Map[String, Int]) derives Optics
  case class IntBox(n: Int) derives Optics

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("BidirectionalLens")(
      suite("Iso")(
        test("reverse swaps to/from"):
          val iso = Iso[Int, String](_.toString, _.toInt)
          val rev = iso.reverse
          assertTrue(rev.to("42") == 42, rev.from(42) == "42")
        ,
        test("reverse twice is identity"):
          val iso = Iso[Int, String](_.toString, _.toInt)
          val rr  = iso.reverse.reverse
          assertTrue(rr.to(7) == iso.to(7), rr.from("7") == iso.from("7")),
      ),
      suite("xmap / imap")(
        test("xmap roundtrip"):
          val lens = Optics[StrBox](_.s).xmap[Int](_.toInt, _.toString)
          val box  = StrBox("42")
          assertTrue(lens.get(box) == 42, lens.set(box, 99) == StrBox("99"))
        ,
        test("imap roundtrip equals xmap"):
          val base = Optics[StrBox](_.s)
          val a    = base.xmap[Int](_.toInt, _.toString)
          val b    = base.imap(Iso[String, Int](_.toInt, _.toString))
          val box  = StrBox("7")
          assertTrue(a.get(box) == b.get(box), a.set(box, 9) == b.set(box, 9))
        ,
        test("set-get law on transformed lens"):
          val lens = Optics[IntBox](_.n).xmap[Long](_.toLong, _.toInt)
          val box  = IntBox(0)
          assertTrue(lens.get(lens.set(box, 123L)) == 123L)
        ,
        test("xmap with non-roundtrippable transform shows lossy behavior"):
          // toUpperCase is not invertible (lower→upper→lower loses original casing)
          // but that's the user's responsibility — verify the pipe still works mechanically
          val lens = Optics[StrBox](_.s).xmap[String](_.toUpperCase, identity)
          val box  = StrBox("hello")
          assertTrue(lens.get(box) == "HELLO", lens.set(box, "WORLD") == StrBox("WORLD")),
      ),
      suite("Collections.Isos")(
        test("listToVector roundtrip"):
          val iso = Isos.listToVector[Int]
          assertTrue(iso.from(iso.to(List(1, 2, 3))) == List(1, 2, 3))
        ,
        test("vectorToList is reverse of listToVector"):
          val a = Isos.listToVector[Int].from(Vector(1, 2))
          val b = Isos.vectorToList[Int].to(Vector(1, 2))
          assertTrue(a == b)
        ,
        test("setToList: List→Set→List dedups"):
          val iso = Isos.setToList[Int]
          assertTrue(iso.from(List(1, 2, 2, 3, 3)) == Set(1, 2, 3))
        ,
        test("mapToList roundtrip preserves entries"):
          val iso = Isos.mapToList[String, Int]
          val m   = Map("a" -> 1, "b" -> 2)
          assertTrue(iso.from(iso.to(m)) == m)
        ,
        test("optionToList: None ↔ Nil"):
          val iso = Isos.optionToList[Int]
          assertTrue(iso.to(None) == Nil, iso.from(Nil) == None)
        ,
        test("optionToList: Some(x) ↔ List(x)"):
          val iso = Isos.optionToList[Int]
          assertTrue(iso.to(Some(5)) == List(5), iso.from(List(5)) == Some(5))
        ,
        test("optionToList: multi-element list collapses to head"):
          val iso = Isos.optionToList[Int]
          assertTrue(iso.from(List(1, 2, 3)) == Some(1)),
      ),
      suite("List extensions")(
        test("mapListBi forward + backward"):
          val lens = Optics[ListBox](_.xs).mapListBi[String](_.toString, _.toInt)
          val box  = ListBox(List(1, 2, 3))
          assertTrue(
            lens.get(box) == List("1", "2", "3"),
            lens.set(box, List("9", "8")) == ListBox(List(9, 8)),
          )
        ,
        test("asVector view + set"):
          val lens = Optics[ListBox](_.xs).asVector
          val box  = ListBox(List(1, 2))
          assertTrue(
            lens.get(box) == Vector(1, 2),
            lens.set(box, Vector(7, 8, 9)) == ListBox(List(7, 8, 9)),
          )
        ,
        test("empty list maps to empty list"):
          val lens = Optics[ListBox](_.xs).mapListBi[String](_.toString, _.toInt)
          assertTrue(lens.get(ListBox(Nil)) == Nil),
      ),
      suite("Option extensions")(
        test("mapOptionBi None stays None"):
          val lens = Optics[OptBox](_.o).mapOptionBi[String](_.toString, _.toInt)
          assertTrue(lens.get(OptBox(None)) == None, lens.set(OptBox(Some(0)), None) == OptBox(None))
        ,
        test("mapOptionBi Some roundtrips"):
          val lens = Optics[OptBox](_.o).mapOptionBi[String](_.toString, _.toInt)
          val box  = OptBox(Some(42))
          assertTrue(lens.get(box) == Some("42"), lens.set(box, Some("7")) == OptBox(Some(7)))
        ,
        test("asList: None ↔ Nil"):
          val lens = Optics[OptBox](_.o).asList
          assertTrue(lens.get(OptBox(None)) == Nil, lens.set(OptBox(Some(0)), Nil) == OptBox(None))
        ,
        test("asList: Some(x) ↔ List(x)"):
          val lens = Optics[OptBox](_.o).asList
          assertTrue(
            lens.get(OptBox(Some(5))) == List(5),
            lens.set(OptBox(None), List(7)) == OptBox(Some(7)),
          )
        ,
        test("withDefault: None reads as default"):
          val lens = Optics[OptBox](_.o).withDefault(0)
          assertTrue(lens.get(OptBox(None)) == 0)
        ,
        test("withDefault: Some(default) reads as default"):
          val lens = Optics[OptBox](_.o).withDefault(0)
          assertTrue(lens.get(OptBox(Some(0))) == 0)
        ,
        test("withDefault: setting default collapses to None"):
          val lens = Optics[OptBox](_.o).withDefault(0)
          assertTrue(lens.set(OptBox(Some(5)), 0) == OptBox(None))
        ,
        test("withDefault: setting non-default writes Some"):
          val lens = Optics[OptBox](_.o).withDefault(0)
          assertTrue(lens.set(OptBox(None), 5) == OptBox(Some(5))),
      ),
      suite("Map extensions")(
        test("mapValuesBi forward + backward"):
          val lens = Optics[MapBox](_.m).mapValuesBi[String](_.toString, _.toInt)
          val box  = MapBox(Map("a" -> 1, "b" -> 2))
          assertTrue(
            lens.get(box) == Map("a" -> "1", "b" -> "2"),
            lens.set(box, Map("c" -> "3")) == MapBox(Map("c" -> 3)),
          )
        ,
        test("empty map maps to empty map"):
          val lens = Optics[MapBox](_.m).mapValuesBi[String](_.toString, _.toInt)
          assertTrue(lens.get(MapBox(Map.empty)) == Map.empty[String, String])
        ,
        test("asList of map roundtrips entries"):
          val lens = Optics[MapBox](_.m).asList
          val box  = MapBox(Map("a" -> 1, "b" -> 2))
          assertTrue(lens.get(box).toMap == box.m)
        ,
        test("keySet get returns current keys"):
          val lens = Optics[MapBox](_.m).keySet
          assertTrue(lens.get(MapBox(Map("a" -> 1, "b" -> 2))) == Set("a", "b"))
        ,
        test("keySet set drops absent keys, keeps existing entries"):
          val lens = Optics[MapBox](_.m).keySet
          val box  = MapBox(Map("a" -> 1, "b" -> 2, "c" -> 3))
          // Keep only "a" — "b" and "c" dropped; "z" is not in the map and is silently ignored
          val result = lens.set(box, Set("a", "z"))
          assertTrue(result == MapBox(Map("a" -> 1)))
        ,
        test("keySet: setting empty set yields empty map"):
          val lens = Optics[MapBox](_.m).keySet
          assertTrue(lens.set(MapBox(Map("a" -> 1)), Set.empty) == MapBox(Map.empty)),
      ),
      suite("Validation.validated")(
        test("get returns Some when predicate holds"):
          val base = Optics[IntBox](_.n)
          val lens = validated(base)(_ > 0)
          assertTrue(lens.get(IntBox(5)) == Some(5))
        ,
        test("get returns None when predicate fails"):
          val base = Optics[IntBox](_.n)
          val lens = validated(base)(_ > 0)
          assertTrue(lens.get(IntBox(-1)) == None)
        ,
        test("set Some(valid) writes through"):
          val base = Optics[IntBox](_.n)
          val lens = validated(base)(_ > 0)
          assertTrue(lens.set(IntBox(0), Some(5)) == IntBox(5))
        ,
        test("set Some(invalid) is no-op"):
          val base = Optics[IntBox](_.n)
          val lens = validated(base)(_ > 0)
          val box  = IntBox(5)
          assertTrue(lens.set(box, Some(-1)) == box)
        ,
        test("set None is no-op (no inverse for predicate failure)"):
          val base = Optics[IntBox](_.n)
          val lens = validated(base)(_ > 0)
          val box  = IntBox(5)
          assertTrue(lens.set(box, None) == box),
      ),
      suite("Validation.filteredList")(
        test("get filters by predicate"):
          val lens = filteredList(Optics[ListBox](_.xs))(_ % 2 == 0)
          assertTrue(lens.get(ListBox(List(1, 2, 3, 4))) == List(2, 4))
        ,
        test("set filters incoming list"):
          val lens = filteredList(Optics[ListBox](_.xs))(_ > 0)
          // Negative values are dropped before being written
          assertTrue(lens.set(ListBox(Nil), List(-1, 2, -3, 4)) == ListBox(List(2, 4)))
        ,
        test("setting empty list clears"):
          val lens = filteredList(Optics[ListBox](_.xs))(_ > 0)
          assertTrue(lens.set(ListBox(List(1, 2, 3)), Nil) == ListBox(Nil))
        ,
        test("set with all-failing list clears"):
          val lens = filteredList(Optics[ListBox](_.xs))(_ > 0)
          assertTrue(lens.set(ListBox(List(1, 2)), List(-1, -2)) == ListBox(Nil)),
      ),
    )
  end spec
end BidirectionalLensSpec
