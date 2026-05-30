package conduit

import zio.*
import zio.test.*

/** End-to-end coverage of the [[Conduit]] dispatch runtime: the `apply` / `run` / `dispatch` loop, follow-up
  * actions, [[Subscribe]] / [[Unsubscribe]] ConduitOps, listener error propagation, and the unsafe API.
  */
object ConduitSpec extends ZIOSpecDefault:

  case class S(value: Int) derives Optics

  enum A extends Action:
    case Inc, Dec
    case Set(v: Int)

  sealed trait Err
  case object Boom extends Err

  def counterHandler[E]: ActionHandler[S, Int, E] =
    handle[S, Int, E](Optics[S](_.value)):
      case A.Inc    => update(_ + 1)
      case A.Dec    => update(_ - 1)
      case A.Set(v) => updated(v)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Conduit")(
      suite("apply + run(terminate=true)")(
        test("processes queued actions FIFO and exits") {
          for
            c <- Conduit(S(0))(counterHandler[Nothing])
            _ <- c(A.Inc, A.Inc, A.Set(10), A.Dec)
            _ <- c.run()
            m <- c.currentModel
          yield assertTrue(m == S(9))
        },
        test("handler-emitted follow-up actions are processed before run() exits") {
          // Regression: the old run(terminate=true) eagerly enqueued Done before draining,
          // dropping follow-ups returned by handlers. Here the first Inc emits a Set(42)
          // follow-up; if drain semantics are correct, the model ends at 42, not 1.
          val followUpHandler: ActionHandler[S, Int, Nothing] =
            handle[S, Int, Nothing](Optics[S](_.value)):
              case A.Inc =>
                m => ZIO.succeed(ActionResult(Optics[S](_.value).set(m, m.value + 1), A.Set(42)))
              case A.Set(v) => updated(v)
              case A.Dec    => update(_ - 1)
          for
            c <- Conduit(S(0))(followUpHandler)
            _ <- c(A.Inc)
            _ <- c.run()
            m <- c.currentModel
          yield assertTrue(m == S(42))
        },
      ),
      suite("Subscribe / Unsubscribe ConduitOps")(
        test("Subscribe op installs a listener that fires on subsequent state changes") {
          for
            c        <- Conduit(S(0))(counterHandler[Nothing])
            calls    <- Ref.make(0)
            listener <- Listener[S, Nothing, Int](Optics[S](_.value), _ => calls.update(_ + 1))
            _        <- c(Subscribe(listener), A.Inc, A.Inc)
            _        <- c.run()
            n        <- calls.get
          yield assertTrue(n == 2)
        },
        test("Unsubscribe op removes a listener") {
          for
            c        <- Conduit(S(0))(counterHandler[Nothing])
            calls    <- Ref.make(0)
            listener <- Listener[S, Nothing, Int](Optics[S](_.value), _ => calls.update(_ + 1))
            _        <- c(Subscribe(listener), A.Inc, Unsubscribe(listener), A.Inc)
            _        <- c.run()
            n        <- calls.get
          yield assertTrue(n == 1)
        },
        test("a handler can return Subscribe as a follow-up action") {
          // The use case the action/op split was designed for: a state transition wires up
          // a new listener as part of its result. The dispatch loop processes the Subscribe
          // op before the next dispatched action runs.
          val seen = scala.collection.mutable.ArrayBuffer.empty[Int]
          val handlerWithDynamicSubscribe: ActionHandler[S, Int, Nothing] =
            handle[S, Int, Nothing](Optics[S](_.value)):
              case A.Inc => update(_ + 1)
              case A.Set(v) =>
                m =>
                  for
                    listener <- Listener[S, Nothing, Int](
                      Optics[S](_.value),
                      x => ZIO.succeed(seen.synchronized { seen += x; () }),
                    )
                  yield ActionResult(Optics[S](_.value).set(m, v), Subscribe(listener))
              case A.Dec => update(_ - 1)
          for
            c <- Conduit(S(0))(handlerWithDynamicSubscribe)
            // First Inc — no dynamic listener yet, nothing observed.
            _ <- c(A.Inc)
            // Set installs the listener AS a follow-up. Inc after sees it.
            _ <- c(A.Set(10), A.Inc, A.Inc)
            _ <- c.run()
          yield assertTrue(seen.toList == List(11, 12))
        },
        test("multiple Subscribe ops install independent listeners") {
          for
            c       <- Conduit(S(0))(counterHandler[Nothing])
            callsA  <- Ref.make(0)
            callsB  <- Ref.make(0)
            la      <- Listener[S, Nothing, Int](Optics[S](_.value), _ => callsA.update(_ + 1))
            lb      <- Listener[S, Nothing, Int](Optics[S](_.value), _ => callsB.update(_ + 1))
            _       <- c(Subscribe(la), Subscribe(lb), A.Inc, A.Inc)
            _       <- c.run()
            a       <- callsA.get
            b       <- callsB.get
          yield assertTrue(a == 2) && assertTrue(b == 2)
        },
        test("Unsubscribe is a no-op when the listener was never subscribed") {
          for
            c        <- Conduit(S(0))(counterHandler[Nothing])
            calls    <- Ref.make(0)
            listener <- Listener[S, Nothing, Int](Optics[S](_.value), _ => calls.update(_ + 1))
            _        <- c(Unsubscribe(listener), A.Inc) // Unsubscribe before any Subscribe — should be harmless.
            _        <- c.run()
            n        <- calls.get
          yield assertTrue(n == 0)
        },
      ),
      suite("unhandled actions")(
        test("E = Nothing produces a defect") {
          // C is a foreign action the counterHandler doesn't match.
          case object Foreign extends Action
          for
            c    <- Conduit(S(0))(counterHandler[Nothing])
            exit <- c.dispatch(Foreign).exit
          yield assertTrue(exit.isFailure)
        },
        test("onUnhandled maps to typed E") {
          case object Foreign extends Action
          val h = counterHandler[Err].onUnhandled(_ => Boom)
          for
            c   <- Conduit(S(0))(h)
            res <- c.dispatch(Foreign).either
          yield assertTrue(res == Left(Boom))
        },
      ),
      suite("listener errors")(
        test("a failing listener aborts the dispatch loop (fail-fast)") {
          val h = counterHandler[Err]
          for
            c        <- Conduit(S(0))(h)
            listener <- Listener[S, Err, Int](Optics[S](_.value), _ => ZIO.fail(Boom))
            _        <- c(Subscribe(listener), A.Inc)
            res      <- c.run().either
          yield assertTrue(res == Left(Boom))
        }
      ),
      suite("unsafe API")(
        test("round-trip: apply / subscribe / run / currentModel") {
          // unsafe API runs against Runtime.default; treat as a smoke test.
          val c     = Conduit.make(S(0))(counterHandler[Nothing])
          val ref   = java.util.concurrent.atomic.AtomicInteger(0)
          val lens  = Optics[S](_.value)
          val _     = c.unsafe.subscribe(lens)(_ => { ref.incrementAndGet(); () })
          c.unsafe(A.Inc, A.Inc, A.Set(7))
          c.unsafe.run()
          val model = c.unsafe.currentModel
          val calls = ref.get
          assertTrue(model == S(7)) && assertTrue(calls == 3)
        },
        test("inline path-based subscribe composes with assertTrue / smart assertions") {
          // Regression: zio-test's smart-assertion macro should not choke on inline path-based
          // subscribe[S](inline path: M => S). If this test compiles, the inline+macro composition works.
          val c   = Conduit.make(S(0))(counterHandler[Nothing])
          val ref = java.util.concurrent.atomic.AtomicInteger(0)
          val _   = c.unsafe.subscribe(_.value)(_ => { ref.incrementAndGet(); () })
          c.unsafe(A.Inc)
          c.unsafe.run()
          assertTrue(c.unsafe.currentModel == S(1))
        },
      ),
    )
end ConduitSpec
