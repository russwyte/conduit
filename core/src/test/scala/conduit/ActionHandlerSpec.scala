package conduit

import zio.*
import zio.test.*

/** Tests for [[ActionHandler]] and its composition combinators (`>>` / `orElse`, `++` / `fold`),
  * plus `onUnhandled` and `widen`.
  */
object ActionHandlerSpec extends ZIOSpecDefault:

  case class M(a: Int, b: Int) derives Optics
  val model = Optics[M]

  enum AAction extends Action:
    case IncA, IncB

  enum BAction extends Action:
    case DoubleA, DoubleB

  enum CAction extends Action:
    case ResetA

  sealed trait MyErr
  case object Boom extends MyErr

  def aHandler[E]: ActionHandler[M, Int, E] =
    handle[M, Int, E](model(_.a)):
      case AAction.IncA => update(_ + 1)
      case AAction.IncB => noChange

  def bHandler[E]: ActionHandler[M, Int, E] =
    handle[M, Int, E](model(_.b)):
      case BAction.DoubleA => update(_ * 2)
      case BAction.DoubleB => update(_ * 2)

  def cHandler[E]: ActionHandler[M, Int, E] =
    handle[M, Int, E](model(_.a)):
      case CAction.ResetA => updated(0)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ActionHandler")(
      suite("orElse / >>")(
        test("first matches → first runs") {
          val h = aHandler[Nothing] >> bHandler[Nothing]
          for r <- h.process(AAction.IncA, M(0, 0))
          yield assertTrue(r.newModel == M(1, 0))
        },
        test("first misses, second matches → second runs") {
          val h = aHandler[Nothing] >> bHandler[Nothing]
          for r <- h.process(BAction.DoubleB, M(3, 5))
          yield assertTrue(r.newModel == M(3, 10))
        },
        test("both miss → unhandled defect (E = Nothing)") {
          val h = aHandler[Nothing] >> bHandler[Nothing]
          for exit <- h.process(CAction.ResetA, M(0, 0)).exit
          yield assertTrue(exit.isFailure)
        },
        test("onUnhandled returns user's typed E") {
          val h: ActionHandler[M, ?, MyErr] =
            (aHandler[MyErr] >> bHandler[MyErr]).onUnhandled(_ => Boom)
          for r <- h.process(CAction.ResetA, M(0, 0)).either
          yield assertTrue(r == Left(Boom))
        },
        test("three-deep composition (a >> b >> c)") {
          val h = aHandler[Nothing] >> bHandler[Nothing] >> cHandler[Nothing]
          for
            r1 <- h.process(AAction.IncA, M(0, 0))
            r2 <- h.process(BAction.DoubleA, M(0, 5))
            r3 <- h.process(CAction.ResetA, M(7, 0))
          yield assertTrue(r1.newModel == M(1, 0)) &&
            assertTrue(r2.newModel == M(0, 10)) &&
            assertTrue(r3.newModel == M(0, 0))
        },
      ),
      suite("fold / ++")(
        test("only first matches → first runs") {
          val h = aHandler[Nothing] ++ bHandler[Nothing]
          for r <- h.process(AAction.IncA, M(0, 0))
          yield assertTrue(r.newModel == M(1, 0))
        },
        test("only second matches → second runs") {
          val h = aHandler[Nothing] ++ bHandler[Nothing]
          for r <- h.process(BAction.DoubleB, M(0, 4))
          yield assertTrue(r.newModel == M(0, 8))
        },
        test("both match → first then second on threaded model") {
          // Action that both handlers respond to: define overlapping pattern.
          // a matches IncA (a + 1); make b also match IncA (b + 10).
          val a2 = handle[M, Int, Nothing](model(_.a)):
            case AAction.IncA => update(_ + 1)
          val b2 = handle[M, Int, Nothing](model(_.b)):
            case AAction.IncA => update(_ + 10)
          val h = a2 ++ b2
          for r <- h.process(AAction.IncA, M(0, 0))
          yield assertTrue(r.newModel == M(1, 10))
        },
        test("both miss → unhandled") {
          val h = aHandler[Nothing] ++ bHandler[Nothing]
          for exit <- h.process(CAction.ResetA, M(0, 0)).exit
          yield assertTrue(exit.isFailure)
        },
      ),
      suite("widen")(
        test("widens E to a supertype") {
          val h: ActionHandler[M, Int, Nothing] = aHandler[Nothing]
          val w: ActionHandler[M, Int, MyErr]   = h.widen[MyErr]
          for r <- w.process(AAction.IncA, M(0, 0))
          yield assertTrue(r.newModel == M(1, 0))
        }
      ),
    )
end ActionHandlerSpec
