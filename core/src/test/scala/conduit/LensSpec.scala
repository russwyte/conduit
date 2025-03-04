package conduit
import zio.*
import zio.test.*

object LensSpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment & Scope, Any] =
    case class Foo(x: Int) derives Optics
    case class Bar(foo: Foo) derives Optics
    suite("Lens")(
      suite("get/set")(
        test("simple"):
          val lens = Optics[Foo](_.x)
          val foo  = Foo(42)
          assertTrue(lens.get(foo) == 42) &&
          assertTrue(lens.set(foo, 43) == Foo(43))
        ,
        test("nested"):
          val lens = Optics[Bar](_.foo.x)
          val bar  = Bar(Foo(42))
          assertTrue(lens.get(bar) == 42) &&
          assertTrue(lens.set(bar, 43) == Bar(Foo(43))),
      ),
      suite("composition")(
        test("simple"):
          val fooLens = Optics[Foo](_.x)
          val barLens = Optics[Bar](_.foo)
          val lens    = barLens >> fooLens
          val bar     = Bar(Foo(42))
          assertTrue(lens.get(bar) == 42) &&
          assertTrue(lens.set(bar, 43) == Bar(Foo(43)))
        ,
        test("by path"):
          val barLens = Optics[Bar](_.foo)
          val lens    = barLens(_.x)
          val bar     = Bar(Foo(42))
          assertTrue(lens.get(bar) == 42) &&
          assertTrue(lens.set(bar, 43) == Bar(Foo(43))),
      ),
    )
  end spec
end LensSpec
