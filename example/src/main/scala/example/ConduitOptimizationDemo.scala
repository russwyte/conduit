package example

import conduit.*
import java.io.IOException
import zio.*

/** Demonstrates Conduit's dispatch optimization - listeners are only notified when the model actually changes. */
object ConduitOptimizationDemo extends ZIOAppDefault:

  case class CounterModel(count: Int) derives Optics

  enum CounterAction extends AppAction:
    case Increment
    case Decrement
    case SetValue(value: Int)
    case NoOp // Action that doesn't change state

  val run =
    for
      _ <- Console.printLine("=== Conduit Dispatch Optimization Demo ===")

      listenerCallCount <- Ref.make(0)

      conduit <- Conduit(CounterModel(0))(
        handle[CounterModel, IOException]:
          case CounterAction.Increment =>
            m => ZIO.succeed(ActionResult(m.copy(count = m.count + 1)))
          case CounterAction.Decrement =>
            m => ZIO.succeed(ActionResult(m.copy(count = m.count - 1)))
          case CounterAction.SetValue(value) =>
            m => ZIO.succeed(ActionResult(m.copy(count = value)))
          case CounterAction.NoOp =>
            m => ZIO.succeed(ActionResult(m)) // Returns same model unchanged
      )

      // Subscribe to count changes
      _ <- conduit.subscribe(_.count) { count =>
        listenerCallCount.update(_ + 1) *>
          Console.printLine(s"Listener called: count = $count")
      }

      _      <- Console.printLine("\n1. Normal operation - should trigger listener")
      _      <- conduit(CounterAction.Increment)
      _      <- ZIO.sleep(100.millis) // Give time for processing
      calls1 <- listenerCallCount.get
      _      <- Console.printLine(s"Listener call count: $calls1")

      _      <- Console.printLine("\n2. NoOp action - should NOT trigger listener")
      _      <- conduit(CounterAction.NoOp)
      _      <- ZIO.sleep(100.millis)
      calls2 <- listenerCallCount.get
      _      <- Console.printLine(s"Listener call count: $calls2")

      _      <- Console.printLine("\n3. Setting to the same value - should NOT trigger listener")
      _      <- conduit(CounterAction.SetValue(1)) // Same value as current
      _      <- ZIO.sleep(100.millis)
      calls3 <- listenerCallCount.get
      _      <- Console.printLine(s"Listener call count: $calls3")

      _      <- Console.printLine("\n4. Setting to a different value - should trigger listener")
      _      <- conduit(CounterAction.SetValue(5))
      _      <- ZIO.sleep(100.millis)
      calls4 <- listenerCallCount.get
      _      <- Console.printLine(s"Listener call count: $calls4")

      _      <- Console.printLine("\n5. Multiple NoOp operations")
      _      <- conduit(CounterAction.NoOp, CounterAction.NoOp, CounterAction.NoOp)
      _      <- ZIO.sleep(100.millis)
      calls5 <- listenerCallCount.get
      _      <- Console.printLine(s"Listener call count: $calls5")

      _      <- Console.printLine("\n6. Final change to verify it still works")
      _      <- conduit(CounterAction.SetValue(10))
      _      <- ZIO.sleep(100.millis)
      calls6 <- listenerCallCount.get
      _      <- Console.printLine(s"Listener call count: $calls6")

      _ <- Console.printLine("\n=== Optimization Summary ===")
      totalActions = 8 // 1 + 1 + 1 + 1 + 3 + 1
      _ <- Console.printLine(s"Total actions dispatched: $totalActions")
      _ <- Console.printLine(s"Listeners triggered: $calls6")
      _ <- Console.printLine("Listeners were only called when the model actually changed!")
      _ <- Console.printLine("This optimization prevents unnecessary lens evaluations and listener notifications.")
    yield ()
end ConduitOptimizationDemo
