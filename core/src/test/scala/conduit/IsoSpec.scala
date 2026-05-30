package conduit

import zio.*
import zio.test.*

import conduit.Iso.*

/** Tests for [[Iso]] and the `imap` / `xmap` lens extensions. Verifies iso round-trips, that `xmap ≡ imap(Iso)`, lens
  * laws on a faithful iso, identity-iso correctness, the documented caveat that lens laws can break on a non-faithful
  * iso, and that derived lenses compose with `focus`.
  */
object IsoSpec extends ZIOSpecDefault:

  case class CountBox(n: Int) derives Optics
  case class Model(count: Int, label: String) derives Optics

  enum A extends Action:
    case SetStr(s: String)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Iso")(
      // ─── core ───────────────────────────────────────────────────────────
      suite("core")(
        test("reverse swaps to/from"):
          val iso: Iso[Int, String] = Iso(_.toString, _.toInt)
          val r: Iso[String, Int]   = iso.reverse
          // r.to is iso.from
          assertTrue(r.to("42") == iso.from("42"))
          // r.from is iso.to
          && assertTrue(r.from(7) == iso.to(7))
          // round-trip through reverse is identity
          && assertTrue(r.to(iso.to(42)) == 42)
        ,
        test("Iso.id is identity in both directions"):
          val iso = Iso.id[Int]
          assertTrue(iso.to(7) == 7) && assertTrue(iso.from(7) == 7),
      ),
      // ─── xmap ≡ imap(Iso(...)) ──────────────────────────────────────────
      suite("xmap and imap")(
        test("xmap equals imap on the same to/from"):
          val base                            = Optics[CountBox](_.n)
          val viaXmap: Lens[CountBox, String] = base.xmap(_.toString, _.toInt)
          val viaImap: Lens[CountBox, String] = base.imap(Iso(_.toString, _.toInt))
          val box                             = CountBox(7)
          assertTrue(viaXmap.get(box) == viaImap.get(box))
          && assertTrue(viaXmap.set(box, "42") == viaImap.set(box, "42")),
      ),
      // ─── lens laws on a faithful iso ────────────────────────────────────
      suite("lens laws — faithful Iso(Int, String) on numeric strings")(
        test("get-set / set-get / set-set hold for a domain where Int↔String round-trips exactly"):
          val asStr = Optics[CountBox](_.n).xmap(_.toString, _.toInt)
          val cases = List(0, 1, -1, 42, Int.MaxValue, Int.MinValue)
          val laws = cases.map { n =>
            val box    = CountBox(n)
            val getSet = asStr.set(box, asStr.get(box)) == box
            val setGet = asStr.get(asStr.set(box, "100")) == "100"
            val setSet = asStr.set(asStr.set(box, "7"), "100") == asStr.set(box, "100")
            getSet && setGet && setSet
          }
          assertTrue(laws.forall(identity)),
      ),
      // ─── identity-iso correctness ───────────────────────────────────────
      suite("identity iso")(
        test("imap(Iso.id) leaves get/set unchanged"):
          val base = Optics[CountBox](_.n)
          val same = base.imap(Iso.id[Int])
          val box  = CountBox(42)
          assertTrue(same.get(box) == base.get(box)) && assertTrue(same.set(box, 7) == base.set(box, 7)),
      ),
      // ─── caveat: non-faithful iso loses laws ────────────────────────────
      suite("caveat: non-faithful Iso")(
        test("set-get fails when from(to(_)) isn't identity"):
          // `to = _.toUpperCase`, `from = identity` is intentionally wrong.
          // After set("hello"), get returns "HELLO" — set-get fails. We document this so users know.
          case class Box(s: String) derives Optics
          val lens = Optics[Box](_.s).xmap(_.toUpperCase, identity)
          val box  = Box("hello")
          assertTrue(lens.get(lens.set(box, "hello")) != "hello"),
      ),
      // ─── composition with focus + handler ───────────────────────────────
      suite("composition with handler DSL")(
        test("xmap inside a focus body works end-to-end"):
          // Action carries a string; we focus on `count` and write through an Int↔String iso.
          val h = handle[Model, Model, Nothing](Optics[Model]):
            case A.SetStr(s) =>
              focus(_.count): // inside this body, ambient lens is Lens[Model, Int]
                outer ?=>
                  val asStr = outer.xmap[String](_.toString, _.toInt)
                  m => ZIO.succeed(ActionResult(asStr.set(m, s)))
          val initial = Model(0, "x")
          for r <- h.process(A.SetStr("42"), initial)
          yield assertTrue(r.newModel.count == 42),
      ),
    )
end IsoSpec
