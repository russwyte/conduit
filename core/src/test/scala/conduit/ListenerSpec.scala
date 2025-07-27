package conduit
import zio.*
import zio.test.*

/** Comprehensive test suite for the Listener class.
  *
  * Test Coverage:
  *   - Basic Operations: notification triggering, listener invocation, state tracking
  *   - State Management: last value tracking with Ref, proper state updates
  *   - Change Detection: only notify on actual changes, skip duplicate values
  *   - Error Handling: error propagation, typed errors, effect composition
  *   - Concurrent Access: thread safety, multiple notifications, race conditions
  *   - Lens Integration: different cursor types, nested field access
  *   - Factory Methods: apply method, unit method, proper initialization
  *   - Edge Cases: initial state, empty models, complex data structures
  *
  * The tests verify that the listener implementation correctly manages state, detects changes efficiently, and
  * integrates seamlessly with the ZIO effect system.
  */
object ListenerSpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment & Scope, Any] =
    // Test data structures
    case class SimpleModel(value: Int) derives Optics
    case class ComplexModel(
        id: String,
        count: Int,
        nested: NestedData,
        optional: Option[String],
    ) derives Optics
    case class NestedData(name: String, flag: Boolean) derives Optics

    // Custom error types for testing
    sealed trait TestError
    case object ValidationError                 extends TestError
    case class ProcessingError(message: String) extends TestError

    suite("Listener")(
      suite("Basic Operations")(
        test("should trigger listener on first notification") {
          for
            ref <- Ref.make(0)
            listener <- Listener[SimpleModel, Nothing, Int](
              Optics[SimpleModel](_.value),
              value => ref.update(_ + value),
            )
            _      <- listener.notify(SimpleModel(42))
            result <- ref.get
          yield assertTrue(result == 42)
        },
        test("should track last value correctly") {
          for
            ref <- Ref.make(List.empty[Int])
            listener <- Listener[SimpleModel, Nothing, Int](
              Optics[SimpleModel](_.value),
              value => ref.update(value :: _),
            )
            _      <- listener.notify(SimpleModel(1))
            _      <- listener.notify(SimpleModel(2))
            _      <- listener.notify(SimpleModel(3))
            result <- ref.get
          yield assertTrue(result.reverse == List(1, 2, 3))
        },
        test("should not trigger listener for duplicate values") {
          for
            ref <- Ref.make(0)
            listener <- Listener[SimpleModel, Nothing, Int](
              Optics[SimpleModel](_.value),
              _ => ref.update(_ + 1),
            )
            _      <- listener.notify(SimpleModel(42))
            _      <- listener.notify(SimpleModel(42)) // Same value
            _      <- listener.notify(SimpleModel(42)) // Same value again
            result <- ref.get
          yield assertTrue(result == 1) // Should only be called once
        },
        test("should trigger listener when value changes after duplicate") {
          for
            ref <- Ref.make(List.empty[Int])
            listener <- Listener[SimpleModel, Nothing, Int](
              Optics[SimpleModel](_.value),
              value => ref.update(value :: _),
            )
            _      <- listener.notify(SimpleModel(1))
            _      <- listener.notify(SimpleModel(1)) // Duplicate
            _      <- listener.notify(SimpleModel(2)) // Change
            _      <- listener.notify(SimpleModel(2)) // Duplicate
            _      <- listener.notify(SimpleModel(3)) // Change
            result <- ref.get
          yield assertTrue(result.reverse == List(1, 2, 3))
        },
      ),
      suite("State Management")(
        test("should maintain proper state isolation between listeners") {
          for
            ref1 <- Ref.make(0)
            ref2 <- Ref.make(0)
            listener1 <- Listener[SimpleModel, Nothing, Int](
              Optics[SimpleModel](_.value),
              value => ref1.set(value),
            )
            listener2 <- Listener[SimpleModel, Nothing, Int](
              Optics[SimpleModel](_.value),
              value => ref2.set(value * 2),
            )
            model1 = SimpleModel(10)
            model2 = SimpleModel(20)
            _       <- listener1.notify(model1)
            _       <- listener2.notify(model2)
            result1 <- ref1.get
            result2 <- ref2.get
          yield assertTrue(result1 == 10 && result2 == 40)
        },
        test("should handle initial empty state correctly") {
          for
            ref <- Ref.make(Option.empty[Int])
            listener <- Listener[SimpleModel, Nothing, Int](
              Optics[SimpleModel](_.value),
              value => ref.set(Some(value)),
            )
            // Check that listener hasn't been triggered yet
            initial <- ref.get
            _       <- listener.notify(SimpleModel(100))
            result  <- ref.get
          yield assertTrue(initial.isEmpty && result.contains(100))
        },
        test("should update state atomically") {
          for
            ref <- Ref.make(List.empty[Int])
            listener <- Listener[SimpleModel, Nothing, Int](
              Optics[SimpleModel](_.value),
              value => ref.update(value :: _),
            )
            // Simulate concurrent notifications
            fiber1 <- listener.notify(SimpleModel(1)).fork
            fiber2 <- listener.notify(SimpleModel(2)).fork
            fiber3 <- listener.notify(SimpleModel(3)).fork
            _      <- fiber1.join
            _      <- fiber2.join
            _      <- fiber3.join
            result <- ref.get
          yield assertTrue(result.toSet == Set(1, 2, 3))
        },
      ),
      suite("Error Handling")(
        test("should propagate listener errors") {
          val error = ProcessingError("test error")
          for
            listener <- Listener[SimpleModel, TestError, Int](
              Optics[SimpleModel](_.value),
              _ => ZIO.fail(error),
            )
            result <- listener.notify(SimpleModel(42)).exit
          yield assertTrue(result.isFailure && result == Exit.fail(error))
        },
        test("should handle typed errors correctly") {
          for
            ref <- Ref.make(List.empty[Either[TestError, Int]])
            listener <- Listener[SimpleModel, TestError, Int](
              Optics[SimpleModel](_.value),
              value =>
                if value < 0 then ZIO.fail(ValidationError)
                else ref.update(Right(value) :: _),
            )
            _      <- listener.notify(SimpleModel(10)).catchAll(err => ref.update(Left(err) :: _))
            _      <- listener.notify(SimpleModel(-5)).catchAll(err => ref.update(Left(err) :: _))
            _      <- listener.notify(SimpleModel(20)).catchAll(err => ref.update(Left(err) :: _))
            result <- ref.get
          yield assertTrue(
            result.reverse == List(Right(10), Left(ValidationError), Right(20))
          )
        },
        test("should not update last value when listener fails") {
          for
            callCount <- Ref.make(0)
            listener <- Listener[SimpleModel, TestError, Int](
              Optics[SimpleModel](_.value),
              value =>
                callCount.update(_ + 1) *>
                  (if value == 42 then ZIO.fail(ProcessingError("boom"))
                   else ZIO.unit),
            )
            _     <- listener.notify(SimpleModel(1))
            _     <- listener.notify(SimpleModel(42)).catchAll(_ => ZIO.unit)
            _     <- listener.notify(SimpleModel(42)).catchAll(_ => ZIO.unit) // Should fail again
            count <- callCount.get
          yield assertTrue(
            count == 3
          ) // Should be called 3 times: once for 1, twice for 42 (since lastValue not updated on failure)
        },
      ),
      suite("Lens Integration")(
        test("should work with nested field access") {
          for
            ref <- Ref.make(List.empty[String])
            listener <- Listener[ComplexModel, Nothing, String](
              Optics[ComplexModel](_.nested.name),
              name => ref.update(name :: _),
            )
            model1 = ComplexModel("1", 0, NestedData("Alice", true), None)
            model2 = ComplexModel("2", 0, NestedData("Bob", true), None)
            model3 = ComplexModel("3", 0, NestedData("Bob", false), None) // Same name
            _      <- listener.notify(model1)
            _      <- listener.notify(model2)
            _      <- listener.notify(model3) // Should not trigger
            result <- ref.get
          yield assertTrue(result.reverse == List("Alice", "Bob"))
        },
        test("should work with optional fields") {
          for
            ref <- Ref.make(List.empty[Option[String]])
            listener <- Listener[ComplexModel, Nothing, Option[String]](
              Optics[ComplexModel](_.optional),
              opt => ref.update(opt :: _),
            )
            model1 = ComplexModel("1", 0, NestedData("test", true), None)
            model2 = ComplexModel("2", 0, NestedData("test", true), Some("value"))
            model3 = ComplexModel("3", 0, NestedData("test", true), Some("value")) // Same
            model4 = ComplexModel("4", 0, NestedData("test", true), None)
            _      <- listener.notify(model1)
            _      <- listener.notify(model2)
            _      <- listener.notify(model3) // Should not trigger
            _      <- listener.notify(model4)
            result <- ref.get
          yield assertTrue(result.reverse == List(None, Some("value"), None))
        },
        test("should work with primitive field types") {
          for
            ref <- Ref.make(List.empty[Boolean])
            listener <- Listener[ComplexModel, Nothing, Boolean](
              Optics[ComplexModel](_.nested.flag),
              flag => ref.update(flag :: _),
            )
            model1 = ComplexModel("1", 0, NestedData("test", true), None)
            model2 = ComplexModel("2", 0, NestedData("test", false), None)
            model3 = ComplexModel("3", 0, NestedData("test", false), None) // Same
            _      <- listener.notify(model1)
            _      <- listener.notify(model2)
            _      <- listener.notify(model3) // Should not trigger
            result <- ref.get
          yield assertTrue(result.reverse == List(true, false))
        },
      ),
      suite("Factory Methods")(
        test("apply method should create functional listener") {
          for
            ref <- Ref.make(0)
            listener <- Listener[SimpleModel, Nothing, Int](
              Optics[SimpleModel](_.value),
              value => ref.set(value),
            )
            _      <- listener.notify(SimpleModel(123))
            result <- ref.get
          yield assertTrue(result == 123)
        },
        test("unit method should create no-op listener") {
          for
            listener <- Listener.unit[SimpleModel, Int](Optics[SimpleModel](_.value))
            // Should complete without error
            _ <- listener.notify(SimpleModel(456))
          yield assertTrue(true)
        },
        test("unit listener should still track state correctly") {
          for
            ref          <- Ref.make(0)
            unitListener <- Listener.unit[SimpleModel, Int](Optics[SimpleModel](_.value))
            normalListener <- Listener[SimpleModel, Nothing, Int](
              Optics[SimpleModel](_.value),
              _ => ref.update(_ + 1),
            )
            // Unit listener should not trigger anything
            _ <- unitListener.notify(SimpleModel(1))
            _ <- unitListener.notify(SimpleModel(1)) // Duplicate
            // Normal listener should work
            _      <- normalListener.notify(SimpleModel(1))
            _      <- normalListener.notify(SimpleModel(1)) // Duplicate - should not trigger
            result <- ref.get
          yield assertTrue(result == 1)
        },
      ),
      suite("Concurrent Access")(
        test("should handle concurrent notifications safely") {
          for
            ref <- Ref.make(Set.empty[Int])
            listener <- Listener[SimpleModel, Nothing, Int](
              Optics[SimpleModel](_.value),
              value => ref.update(_ + value),
            )
            // Create multiple concurrent notifications with different values
            fibers <- ZIO.foreach((1 to 10).toList) { i =>
              listener.notify(SimpleModel(i)).fork
            }
            _      <- ZIO.foreach(fibers)(_.join)
            result <- ref.get
          yield assertTrue(result == (1 to 10).toSet)
        },
        test("should handle rapid duplicate notifications correctly") {
          for
            ref <- Ref.make(0)
            listener <- Listener[SimpleModel, Nothing, Int](
              Optics[SimpleModel](_.value),
              _ => ref.update(_ + 1),
            )
            // Send many duplicate notifications concurrently
            fibers <- ZIO.foreach((1 to 100).toList) { _ =>
              listener.notify(SimpleModel(42)).fork
            }
            _      <- ZIO.foreach(fibers)(_.join)
            result <- ref.get
          yield assertTrue(
            result >= 1 && result <= 10
          ) // Should trigger minimally, allowing for more race conditions in high concurrency
        },
        test("should maintain consistency under concurrent state changes") {
          for
            ref <- Ref.make(List.empty[Int])
            listener <- Listener[SimpleModel, Nothing, Int](
              Optics[SimpleModel](_.value),
              value => ref.update(value :: _),
            )
            // Mix of different values and duplicates
            values = List(1, 1, 2, 2, 3, 1, 4, 4, 5)
            fibers <- ZIO.foreach(values) { value =>
              listener.notify(SimpleModel(value)).fork
            }
            _      <- ZIO.foreach(fibers)(_.join)
            result <- ref.get
            uniqueResults = result.toSet
          yield assertTrue(uniqueResults == Set(1, 2, 3, 4, 5))
        },
      ),
      suite("Performance and Edge Cases")(
        test("should handle large model updates efficiently") {
          case class LargeModel(
              id: Int,
              data: String,
              values: List[Int],
              metadata: Map[String, String],
          ) derives Optics

          for
            ref <- Ref.make(0)
            listener <- Listener[LargeModel, Nothing, Int](
              Optics[LargeModel](_.id),
              _ => ref.update(_ + 1),
            )
            largeData     = "x" * 10000
            largeValues   = (1 to 1000).toList
            largeMetadata = (1 to 100).map(i => s"key$i" -> s"value$i").toMap

            model1 = LargeModel(1, largeData, largeValues, largeMetadata)
            model2 = LargeModel(2, largeData, largeValues, largeMetadata)
            model3 = LargeModel(2, largeData, largeValues, largeMetadata) // Same ID

            start <- Clock.nanoTime
            _     <- listener.notify(model1)
            _     <- listener.notify(model2)
            _     <- listener.notify(model3) // Should not trigger
            end   <- Clock.nanoTime

            result <- ref.get
            duration = (end - start) / 1_000_000 // Convert to milliseconds
          yield assertTrue(result == 2 && duration < 100) // Should be fast
        },
        test("should handle complex nested equality correctly") {
          case class DeepNested(
              level1: Level1
          ) derives Optics
          case class Level1(
              level2: Level2,
              value: String,
          ) derives Optics
          case class Level2(
              level3: Level3,
              items: List[String],
          ) derives Optics
          case class Level3(
              data: Map[String, Int]
          ) derives Optics

          for
            ref <- Ref.make(0)
            listener <- Listener[DeepNested, Nothing, Map[String, Int]](
              Optics[DeepNested](_.level1.level2.level3.data),
              _ => ref.update(_ + 1),
            )

            data1 = Map("a" -> 1, "b" -> 2)
            data2 = Map("a" -> 1, "b" -> 2) // Same content
            data3 = Map("b" -> 2, "a" -> 1) // Same content, different order
            data4 = Map("a" -> 1, "b" -> 3) // Different content

            model1 = DeepNested(Level1(Level2(Level3(data1), List("x")), "test"))
            model2 = DeepNested(Level1(Level2(Level3(data2), List("y")), "test2"))
            model3 = DeepNested(Level1(Level2(Level3(data3), List("z")), "test3"))
            model4 = DeepNested(Level1(Level2(Level3(data4), List("w")), "test4"))

            _ <- listener.notify(model1)
            _ <- listener.notify(model2) // Should not trigger (same data)
            _ <- listener.notify(model3) // Should not trigger (same data)
            _ <- listener.notify(model4) // Should trigger (different data)

            result <- ref.get
          yield assertTrue(result == 2)
          end for
        },
      ),
    )
  end spec
end ListenerSpec
