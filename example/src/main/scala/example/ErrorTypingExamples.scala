package example

import conduit.*
import zio.*

// Example demonstrating the strongly-typed error API

// Define custom error types
sealed trait ValidationError         extends Throwable
case class InvalidAge(age: Int)      extends ValidationError
case class InvalidName(name: String) extends ValidationError

// Model and actions
case class User(name: String, age: Int, email: String) derives Optics

object User:
  case class UpdateName(name: String)   extends AppAction
  case class UpdateAge(age: Int)        extends AppAction
  case class UpdateEmail(email: String) extends AppAction

  val model = Optics[User]

  // Handler with ValidationError type
  def validatingHandler: ActionHandler[User, ?, ValidationError] =
    handle[User, User, ValidationError](model):
      case UpdateName(name) if name.trim.nonEmpty =>
        m => ZIO.succeed(ActionResult(m.copy(name = name.trim)))
      case UpdateName(name) =>
        m => ZIO.fail(InvalidName(name))
      case UpdateAge(age) if age >= 0 && age <= 150 =>
        m => ZIO.succeed(ActionResult(m.copy(age = age)))
      case UpdateAge(age) =>
        m => ZIO.fail(InvalidAge(age))
      case UpdateEmail(email) =>
        m => ZIO.succeed(ActionResult(m.copy(email = email)))

  // Handler with Throwable (for effects that can throw)
  def simpleHandler: ActionHandler[User, ?, Throwable] =
    handle[User, User, Throwable](model):
      case UpdateName(name) =>
        m => ZIO.succeed(ActionResult(m.copy(name = name)))
      case UpdateAge(age) =>
        m => ZIO.succeed(ActionResult(m.copy(age = age)))
      case UpdateEmail(email) =>
        m => ZIO.succeed(ActionResult(m.copy(email = email)))

end User

object StronglyTypedErrorExample extends ZIOAppDefault:
  def run =
    for
      // Create conduit with ValidationError
      validatingConduit <- Conduit(User("Alice", 30, "alice@example.com"))(User.validatingHandler)

      // Test valid update
      _     <- validatingConduit(User.UpdateName("Bob"))
      user1 <- validatingConduit.currentModel
      _     <- Console.printLine(s"Valid update: $user1")

      // Test invalid update (this will fail with ValidationError)
      result1 <- validatingConduit
        .dispatch(User.UpdateAge(-5))
        .catchAll(err => Console.printLine(s"Validation failed: $err").as(User.UpdateAge(0)))

      // Create conduit with Throwable
      simpleConduit <- Conduit(User("Charlie", 25, "charlie@example.com"))(User.simpleHandler)

      _     <- simpleConduit(User.UpdateName("David"))
      user2 <- simpleConduit.currentModel
      _     <- Console.printLine(s"Simple update: $user2")
    yield ()
end StronglyTypedErrorExample

// Example showing how to combine different error types
object ErrorCompositionExample:

  // Different error types for different domains
  sealed trait BusinessError
  case class DuplicateUser(email: String) extends BusinessError

  sealed trait NetworkError
  case class ConnectionTimeout() extends NetworkError

  // Handlers with specific error types
  def businessHandler: ActionHandler[User, ?, BusinessError] =
    handle[User, User, BusinessError](User.model):
      case User.UpdateEmail(email) if email.contains("duplicate") =>
        m => ZIO.fail(DuplicateUser(email))
      case _ =>
        m => ZIO.succeed(ActionResult.terminal(m))

  def networkHandler: ActionHandler[User, ?, NetworkError] =
    handle[User, User, NetworkError](User.model):
      case User.UpdateName("timeout") =>
        m => ZIO.fail(ConnectionTimeout())
      case _ =>
        m => ZIO.succeed(ActionResult.terminal(m))

  // Combine by widening to common supertype
  def combinedHandler: ActionHandler[User, ?, Throwable] =
    businessHandler.asInstanceOf[ActionHandler[User, ?, Throwable]] >>
      networkHandler.asInstanceOf[ActionHandler[User, ?, Throwable]]

end ErrorCompositionExample
