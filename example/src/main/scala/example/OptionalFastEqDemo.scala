package example

import conduit.*
import java.io.IOException
import zio.*

/** Demonstrates that FastEq is completely optional - no given instances needed for basic usage. */
object OptionalFastEqDemo extends ZIOAppDefault:

  // Model without any FastEq instances defined
  case class SimpleModel(name: String, count: Int) derives Optics

  // Actions for the model
  enum SimpleAction extends AppAction:
    case UpdateName(newName: String)
    case Increment
    case Decrement

  val run =
    for
      _ <- Console.printLine("=== FastEq Optional Demo ===")
      _ <- Console.printLine("This demo shows that no FastEq instances are required!")

      // Create conduit without defining any FastEq instances
      conduit <- Conduit(SimpleModel("test", 0))(
        handle[SimpleModel, IOException]:
          case SimpleAction.UpdateName(name) =>
            m => ZIO.succeed(ActionResult(m.copy(name = name)))
          case SimpleAction.Increment =>
            m => ZIO.succeed(ActionResult(m.copy(count = m.count + 1)))
          case SimpleAction.Decrement =>
            m => ZIO.succeed(ActionResult(m.copy(count = m.count - 1)))
      )

      // Subscribe to changes - FastEq will automatically fall back to standard equality
      _ <- conduit.subscribe(_.name) { name =>
        Console.printLine(s"Name changed to: $name")
      }

      _ <- conduit.subscribe(_.count) { count =>
        Console.printLine(s"Count changed to: $count")
      }

      // Test the functionality
      _ <- conduit(
        SimpleAction.UpdateName("Hello"),
        SimpleAction.Increment,
        SimpleAction.Increment,
        SimpleAction.UpdateName("World"),
        SimpleAction.Decrement,
      )

      _ <- Console.printLine("=== Demo Complete - No FastEq instances were needed! ===")
    yield ()
end OptionalFastEqDemo
