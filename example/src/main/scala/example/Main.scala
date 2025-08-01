package example
import conduit.*

import java.io.IOException
import zio.*

case class Pet(name: String, age: Int)
object Pet:
  case class ChangeName(name: String) extends AppAction
  case class ChangeAge(age: Int)      extends AppAction
  def handler[M, E](pet: Lens[M, Pet]) =
    val ageHandler = handle(pet(_.age)):
      case ChangeAge(age) => updated(age)
    val nameHandler = handle(pet(_.name)):
      case ChangeName(name) => updated(name)
    ageHandler >> nameHandler // Combine the two handlers using orElse
end Pet

case class Model(counter: Int, pet: Pet = Pet("Fido", 2)) derives Optics

// Define custom error types for strongly typed error example
sealed trait ValidationError         extends Throwable
case class InvalidAge(age: Int)      extends ValidationError
case class InvalidName(name: String) extends ValidationError

// User model for error typing example
case class User(name: String, age: Int, email: String) derives Optics

object User:
  case class UpdateName(name: String)   extends AppAction
  case class UpdateAge(age: Int)        extends AppAction
  case class UpdateEmail(email: String) extends AppAction

  val model = Optics[User]

  // Handler with ValidationError type using ActionHandler functions
  def validatingHandler: ActionHandler[User, ?, ValidationError] =
    val nameHandler = handle[User, String, ValidationError](model(_.name)):
      case UpdateName(name) if name.trim.nonEmpty => updated(name.trim)
      case UpdateName(name)                       => m => ZIO.fail(InvalidName(name))

    val ageHandler = handle[User, Int, ValidationError](model(_.age)):
      case UpdateAge(age) if age >= 0 && age <= 150 => updated(age)
      case UpdateAge(age)                           => m => ZIO.fail(InvalidAge(age))

    val emailHandler = handle[User, String, ValidationError](model(_.email)):
      case UpdateEmail(email) => updated(email)

    nameHandler >> ageHandler >> emailHandler
  end validatingHandler

  // Handler with Throwable using ActionHandler functions
  def simpleHandler: ActionHandler[User, ?, Throwable] =
    val nameHandler = handle[User, String, Throwable](model(_.name)):
      case UpdateName(name) => updated(name)

    val ageHandler = handle[User, Int, Throwable](model(_.age)):
      case UpdateAge(age) => updated(age)

    val emailHandler = handle[User, String, Throwable](model(_.email)):
      case UpdateEmail(email) => updated(email)

    nameHandler >> ageHandler >> emailHandler
  end simpleHandler
end User

object Model:
  val model = Optics[Model]

  enum Action extends AppAction:
    case Increment extends Action
    case Decrement extends Action

  def counterHandler[E] =
    handle(model(_.counter)):
      case Action.Increment => update(_ + 1)
      case Action.Decrement => update(_ - 1)

  def logger = handle[Model, IOException]:
    // log the current model state for every action
    case _ =>
      effectOnly: m =>
        Console.printLine(s"Foo: $m")

  def handler =
    (counterHandler[IOException] >> Pet.handler[Model, IOException](model(_.pet))) ++
      logger
end Model

object MyApp extends ZIOAppDefault:
  import Model.Action as model
  import Pet as pet

  def basicExample: ZIO[Any, IOException, Unit] =
    for
      _ <- Console.printLine("=== Running Basic Example ===")
      c <- Conduit(Model(0))(Model.handler)
      _ = c.unsafe(model.Increment)
      _ <- c(
        model.Increment,
        model.Increment,
        model.Increment,
        model.Decrement,
        pet.ChangeName("Rex"),
        pet.ChangeAge(3),
      )
      _ <- c(model.Increment)
      _ <- c.run()
      _ <- Console.printLine("=== Basic Example Complete ===\n")
    yield ()

  def stronglyTypedErrorExample: ZIO[Any, IOException, Unit] =
    for
      _ <- Console.printLine("=== Running Strongly Typed Error Example ===")
      // Create conduit with ValidationError
      validatingConduit <- Conduit(User("Alice", 30, "alice@example.com"))(User.validatingHandler)

      // Test valid update
      _     <- validatingConduit(User.UpdateName("Bob"))
      user1 <- validatingConduit.currentModel
      _     <- Console.printLine(s"Valid update: $user1")

      // Test invalid update (this will fail with ValidationError)
      _ <- validatingConduit
        .dispatch(User.UpdateAge(-5))
        .catchAll(err => Console.printLine(s"Validation failed: $err").as(User.UpdateAge(0)))

      // Create conduit with Throwable
      simpleConduit <- Conduit(User("Charlie", 25, "charlie@example.com"))(User.simpleHandler)

      _     <- simpleConduit(User.UpdateName("David"))
      user2 <- simpleConduit.currentModel
      _     <- Console.printLine(s"Simple update: $user2")
      _     <- Console.printLine("=== Strongly Typed Error Example Complete ===\n")
    yield ()

  val run =
    for
      _ <- basicExample
      _ <- stronglyTypedErrorExample
    yield ()
end MyApp
