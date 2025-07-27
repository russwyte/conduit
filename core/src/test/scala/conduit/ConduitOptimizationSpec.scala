package conduit

import zio.*
import zio.test.*

object ConduitOptimizationSpec extends ZIOSpecDefault:

  case class TestModel(value: Int) derives Optics

  enum TestAction extends AppAction:
    case Increment
    case NoOp
    case SetValue(v: Int)

  case class SimpleModelWithMetadata(value: Int, metadata: String) derives Optics
  object SimpleModelWithMetadata:
    // FastEq instance that ignores metadata, only compares value
    given FastEq[SimpleModelWithMetadata] = FastEq.instance { (a, b) =>
      a.value == b.value
    }

  enum MetadataAction extends AppAction:
    case UpdateMetadata(meta: String)
    case UpdateValue(v: Int)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Conduit Dispatch Optimization")(
      test("should not notify listeners when model doesn't change") {
        for
          listenerCallCount <- Ref.make(0)
          conduit <- Conduit(TestModel(0))(
            handle[TestModel, Nothing]:
              case TestAction.Increment =>
                m => ZIO.succeed(ActionResult(m.copy(value = m.value + 1)))
              case TestAction.NoOp =>
                m => ZIO.succeed(ActionResult(m)) // Same model - no change
              case TestAction.SetValue(v) =>
                m => ZIO.succeed(ActionResult(m.copy(value = v)))
          )

          // Subscribe to changes
          _ <- conduit.subscribe(_.value) { x =>
            println(s"called: $x")
            listenerCallCount.update(_ + 1)
          }

          // Test 1: Normal operation should trigger listener
          _      <- conduit.dispatch(TestAction.Increment)
          calls1 <- listenerCallCount.get

          // Test 2: NoOp should NOT trigger listener (optimization kicks in)
          _      <- conduit.dispatch(TestAction.NoOp)
          calls2 <- listenerCallCount.get

          // Test 3: Setting to same value should NOT trigger listener
          _      <- conduit.dispatch(TestAction.SetValue(1)) // Same as current value
          calls3 <- listenerCallCount.get

          // Test 4: Setting to different value should trigger listener
          _      <- conduit.dispatch(TestAction.SetValue(5))
          calls4 <- listenerCallCount.get

          // Test 5: Multiple NoOps should not trigger listener
          _      <- conduit.dispatch(TestAction.NoOp)
          _      <- conduit.dispatch(TestAction.NoOp)
          _      <- conduit.dispatch(TestAction.NoOp)
          calls5 <- listenerCallCount.get
        yield assertTrue(calls1 == 1) && // First increment triggered
          assertTrue(calls2 == 1) &&     // NoOp did NOT trigger
          assertTrue(calls3 == 1) &&     // Same value did NOT trigger
          assertTrue(calls4 == 2) &&     // Different value triggered
          assertTrue(calls5 == 2)        // Multiple NoOps did NOT trigger
      },
      test("should always update state even when model doesn't change") {
        for
          conduit <- Conduit(TestModel(42))(
            handle[TestModel, Nothing]:
              case TestAction.NoOp =>
                m => ZIO.succeed(ActionResult(m)) // Same model
          )

          // Initial state
          initial <- conduit.currentModel

          // Perform NoOp
          _ <- conduit.dispatch(TestAction.NoOp)

          // State should still be accessible (state update happened)
          finalState <- conduit.currentModel
        yield assertTrue(initial == TestModel(42)) &&
          assertTrue(finalState == TestModel(42)) &&
          assertTrue(initial == finalState)
      },
      test("FastEq typeclass should work correctly") {
        // Test our custom FastEq instance directly
        val model1 = SimpleModelWithMetadata(42, "initial")
        val model2 = SimpleModelWithMetadata(42, "changed") // Same value, different metadata
        val model3 = SimpleModelWithMetadata(99, "initial") // Different value, same metadata
        val model4 = SimpleModelWithMetadata(42, "initial") // Exact same

        for
          // Test FastEq.get retrieves our custom instance
          fastEq <- ZIO.succeed(FastEq.get[SimpleModelWithMetadata])

          // Test cases
          result1 <- ZIO.succeed(fastEq.eqv(model1, model2)) // Should be true (same value, ignore metadata)
          result2 <- ZIO.succeed(fastEq.eqv(model1, model3)) // Should be false (different value)
          result3 <- ZIO.succeed(fastEq.eqv(model1, model4)) // Should be true (identical)
          result4 <- ZIO.succeed(fastEq.eqv(model1, model1)) // Should be true (reflexive)
        yield assertTrue(result1) && // Same value, different metadata = equal
          assertTrue(!result2) &&    // Different value = not equal
          assertTrue(result3) &&     // Identical = equal
          assertTrue(result4)        // Reflexive = equal
      },
    ) @@ TestAspect.withLiveClock
end ConduitOptimizationSpec
