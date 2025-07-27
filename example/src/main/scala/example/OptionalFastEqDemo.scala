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

      // Create conduit using ActionHandler functions pattern
      simpleModelOptics = Optics[SimpleModel]
      nameHandler = handle[SimpleModel, String, IOException](simpleModelOptics(_.name)):
        case SimpleAction.UpdateName(name) => updated(name)

      countHandler = handle[SimpleModel, Int, IOException](simpleModelOptics(_.count)):
        case SimpleAction.Increment => update(_ + 1)
        case SimpleAction.Decrement => update(_ - 1)

      conduit <- Conduit(SimpleModel("test", 0))(nameHandler >> countHandler)

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
